package com.onboarding.backend.schedule

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.NotificationCategory
import com.onboarding.backend.common.NotifyEvent
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.badRequest
import com.onboarding.backend.common.conflict
import com.onboarding.backend.common.forbidden
import com.onboarding.backend.common.notFound
import com.onboarding.backend.organization.OrganizationDirectory
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import net.fortuna.ical4j.model.Recur
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

enum class Visibility { PERSONAL, COMPANY }

@Entity
@Table(name = "schedules")
class Schedule(
    @Id val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val ownerId: UUID,
    var title: String,
    var startAt: Instant,
    var endAt: Instant,
    @Enumerated(EnumType.STRING) var visibility: Visibility,
    var memo: String? = null,
    var recurrenceRule: String? = null,
    var holidayPolicy: String? = null,
    @Version var version: Long = 0,
    val createdAt: Instant = Instant.now(),
)

interface ScheduleRepository : JpaRepository<Schedule, UUID> {
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): Schedule?

    /** 기간에 걸치는 일정 + 기간 종료 전에 시작한 반복 일정. 개인 일정은 본인 것만. */
    @Query(
        """
        select s from Schedule s
        where s.companyId = :companyId and s.startAt <= :to
          and (s.endAt >= :from or s.recurrenceRule is not null)
          and (s.visibility = com.onboarding.backend.schedule.Visibility.COMPANY or s.ownerId = :userId)
        """,
    )
    fun findVisible(companyId: UUID, userId: UUID, from: Instant, to: Instant): List<Schedule>
}

data class ScheduleCreateRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    val startAt: Instant,
    val endAt: Instant,
    val visibility: Visibility,
    @field:Size(max = 2000) val memo: String? = null,
    @field:Size(max = 500) val recurrenceRule: String? = null,
    @field:Size(max = 50) val holidayPolicy: String? = null,
)

data class ScheduleUpdateRequest(
    @field:Size(min = 1, max = 200) val title: String? = null,
    val startAt: Instant? = null,
    val endAt: Instant? = null,
    val visibility: Visibility? = null,
    @field:Size(max = 2000) val memo: String? = null,
    @field:Size(max = 500) val recurrenceRule: String? = null,
    @field:Size(max = 50) val holidayPolicy: String? = null,
    val version: Long,
)

data class ScheduleDto(
    val id: UUID,
    val title: String,
    val startAt: Instant,
    val endAt: Instant,
    val visibility: Visibility,
    val memo: String?,
    val recurrenceRule: String?,
    val holidayPolicy: String?,
    val ownerId: UUID,
    val version: Long,
)
data class ScheduleListResponse(val items: List<ScheduleDto>)
data class ScheduleResponse(val schedule: ScheduleDto)

@RestController
@RequestMapping("/api/v1/schedules")
@Transactional
class ScheduleController(
    private val schedules: ScheduleRepository,
    private val directory: OrganizationDirectory,
    private val events: ApplicationEventPublisher,
    @Value("\${app.time-zone}") timeZone: String,
) {
    private val zone = ZoneId.of(timeZone)

    /** SCH-001: 반복 일정은 조회 기간 안의 회차별로 펼쳐서 돌려준다. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
        @RequestParam(required = false) visibility: Visibility?,
    ): ApiResponse<ScheduleListResponse> {
        if (from.isAfter(to)) throw badRequest("조회 기간이 올바르지 않습니다.")
        val items = schedules.findVisible(me.companyId, me.id, from, to)
            .filter { visibility == null || it.visibility == visibility }
            .flatMap { s -> occurrences(s, from, to).map { (start, end) -> dto(s, start, end) } }
            .sortedBy { it.startAt }
        return ApiResponse(ScheduleListResponse(items))
    }

    /** SCH-002 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal me: AuthUser, @Valid @RequestBody req: ScheduleCreateRequest): ApiResponse<ScheduleResponse> {
        if (req.visibility == Visibility.COMPANY) requireCompanyManager(me)
        validate(req.startAt, req.endAt, req.recurrenceRule)
        val saved = schedules.save(
            Schedule(
                companyId = me.companyId, ownerId = me.id, title = req.title.trim(), startAt = req.startAt, endAt = req.endAt,
                visibility = req.visibility, memo = req.memo, recurrenceRule = req.recurrenceRule?.ifBlank { null },
                holidayPolicy = req.holidayPolicy,
            ),
        )
        return ApiResponse(ScheduleResponse(dto(saved)))
    }

    /** SCH-003: version이 현재 값과 다르면 409. */
    @PatchMapping("/{scheduleId}")
    fun update(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable scheduleId: UUID,
        @Valid @RequestBody req: ScheduleUpdateRequest,
    ): ApiResponse<ScheduleResponse> {
        val s = editable(me, scheduleId)
        if (req.visibility == Visibility.COMPANY) requireCompanyManager(me)
        if (req.version != s.version) throw conflict("다른 변경이 먼저 반영되었습니다. 최신 상태를 다시 불러오세요.")
        val start = req.startAt ?: s.startAt
        val end = req.endAt ?: s.endAt
        val rule = if (req.recurrenceRule != null) req.recurrenceRule.ifBlank { null } else s.recurrenceRule
        validate(start, end, rule)
        val wasCompany = s.visibility == Visibility.COMPANY
        req.title?.let { s.title = it.trim() }
        s.startAt = start
        s.endAt = end
        s.recurrenceRule = rule
        req.visibility?.let { s.visibility = it }
        req.memo?.let { s.memo = it }
        req.holidayPolicy?.let { s.holidayPolicy = it }
        val saved = schedules.saveAndFlush(s)
        if (wasCompany) notifyCompany(me, "회사 일정이 수정되었습니다: ${saved.title}", saved.id)
        return ApiResponse(ScheduleResponse(dto(saved)))
    }

    /** SCH-004 */
    @DeleteMapping("/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal me: AuthUser, @PathVariable scheduleId: UUID) {
        val s = editable(me, scheduleId)
        schedules.delete(s)
        if (s.visibility == Visibility.COMPANY) notifyCompany(me, "회사 일정이 삭제되었습니다: ${s.title}", null)
    }

    /** 개인 일정은 작성자만, 회사 일정은 작성자와 무관하게 멘토가 관리한다. */
    private fun editable(me: AuthUser, id: UUID): Schedule {
        val s = schedules.findByIdAndCompanyId(id, me.companyId) ?: throw notFound()
        if (s.visibility == Visibility.COMPANY) requireCompanyManager(me) else if (s.ownerId != me.id) throw forbidden()
        return s
    }

    private fun requireCompanyManager(me: AuthUser) {
        if (me.role != Role.MENTOR) throw forbidden("회사 일정은 멘토만 관리할 수 있습니다.")
    }

    private fun validate(start: Instant, end: Instant, rule: String?) {
        if (end.isBefore(start)) throw badRequest("종료가 시작보다 빠를 수 없습니다.")
        if (!rule.isNullOrBlank()) {
            runCatching { Recur<ZonedDateTime>(rule) }.getOrElse { throw badRequest("반복 규칙 형식이 올바르지 않습니다.") }
        }
    }

    /** 회사 일정 수정·삭제는 회사 구성원 전원에게 가는 필수 알림이다(행위자 제외). */
    private fun notifyCompany(me: AuthUser, title: String, scheduleId: UUID?) {
        events.publishEvent(
            NotifyEvent(
                companyId = me.companyId, actorId = me.id, recipientIds = directory.activeUserIds(me.companyId),
                category = NotificationCategory.COMPANY_SCHEDULE, mandatory = true, title = title,
                linkType = scheduleId?.let { "schedule" }, linkId = scheduleId,
            ),
        )
    }

    private fun occurrences(s: Schedule, from: Instant, to: Instant): List<Pair<Instant, Instant>> {
        val rule = s.recurrenceRule
        if (rule.isNullOrBlank()) {
            return if (!s.startAt.isAfter(to) && !s.endAt.isBefore(from)) listOf(s.startAt to s.endAt) else emptyList()
        }
        val length = Duration.between(s.startAt, s.endAt)
        val starts = Recur<ZonedDateTime>(rule).getDates(s.startAt.atZone(zone), from.minus(length).atZone(zone), to.atZone(zone))
        return starts.map { it.toInstant() to it.toInstant().plus(length) }
    }

    private fun dto(s: Schedule, start: Instant = s.startAt, end: Instant = s.endAt) = ScheduleDto(
        s.id, s.title, start, end, s.visibility, s.memo, s.recurrenceRule, s.holidayPolicy, s.ownerId, s.version,
    )
}
