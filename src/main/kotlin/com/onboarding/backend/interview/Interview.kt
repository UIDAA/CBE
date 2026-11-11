package com.onboarding.backend.interview

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.NotificationCategory
import com.onboarding.backend.common.NotifyEvent
import com.onboarding.backend.common.Paged
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.badRequest
import com.onboarding.backend.common.conflict
import com.onboarding.backend.common.forbidden
import com.onboarding.backend.common.notFound
import com.onboarding.backend.common.pageOf
import com.onboarding.backend.common.toPaged
import com.onboarding.backend.organization.OrganizationDirectory
import com.onboarding.backend.organization.PersonRef
import com.onboarding.backend.organization.UserStatus
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.persistence.criteria.Predicate
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

enum class InterviewStatus { PENDING, APPROVED, REJECTED }

/** INT-002 direction. 생략하면 내가 보냈거나 받은 신청, ALL은 회사 전체 현황(HR·멘토만). */
enum class Direction { RECEIVED, SENT, ALL }

@Entity
@Table(name = "interviews")
class Interview(
    @Id val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val requesterId: UUID,
    val targetId: UUID,
    val preferredAt: Instant,
    val reason: String,
    @Enumerated(EnumType.STRING) var status: InterviewStatus = InterviewStatus.PENDING,
    var confirmedAt: Instant? = null,
    var rejectReason: String? = null,
    @Version var version: Long = 0,
    val createdAt: Instant = Instant.now(),
) {
    fun involves(userId: UUID) = requesterId == userId || targetId == userId
}

interface InterviewRepository : JpaRepository<Interview, UUID>, JpaSpecificationExecutor<Interview> {
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): Interview?
    fun existsByRequesterIdAndTargetIdAndPreferredAtAndStatus(
        requesterId: UUID, targetId: UUID, preferredAt: Instant, status: InterviewStatus,
    ): Boolean
    fun countByTargetIdAndStatus(targetId: UUID, status: InterviewStatus): Long
    fun countByCompanyIdAndStatus(companyId: UUID, status: InterviewStatus): Long

    /** 해당 사용자가 같은 시각에 이미 확정한 면담이 있는가. */
    @Query(
        """
        select count(i) > 0 from Interview i
        where i.status = com.onboarding.backend.interview.InterviewStatus.APPROVED and i.confirmedAt = :at
          and (i.requesterId = :userId or i.targetId = :userId)
        """,
    )
    fun hasApprovedAt(userId: UUID, at: Instant): Boolean
}

data class InterviewCreateRequest(
    val targetUserId: UUID,
    val preferredAt: Instant,
    @field:NotBlank @field:Size(max = 200) val reason: String,
)
data class InterviewApproveRequest(val confirmedAt: Instant? = null)
data class InterviewRejectRequest(@field:NotBlank @field:Size(max = 200) val reason: String)

data class InterviewDto(
    val id: UUID,
    val requester: PersonRef,
    val target: PersonRef,
    val preferredAt: Instant,
    val confirmedAt: Instant?,
    val status: InterviewStatus,
    /** 사유·거절 사유는 당사자에게만 내려간다. 그 외에는 null. */
    val reason: String?,
    val rejectReason: String?,
    val createdAt: Instant,
)
data class InterviewResponse(val interview: InterviewDto)

@RestController
@RequestMapping("/api/v1/interviews")
@Transactional
class InterviewController(
    private val interviews: InterviewRepository,
    private val directory: OrganizationDirectory,
    private val events: ApplicationEventPublisher,
) {
    /** INT-001: 회사 내 누구나 누구에게나 신청한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal me: AuthUser, @Valid @RequestBody req: InterviewCreateRequest): ApiResponse<InterviewResponse> {
        if (!req.preferredAt.isAfter(Instant.now())) throw badRequest("희망 일시는 현재 이후여야 합니다.")
        if (req.targetUserId == me.id) throw badRequest("본인에게는 신청할 수 없습니다.")
        val target = directory.user(me.companyId, req.targetUserId)?.takeIf { it.status == UserStatus.ACTIVE }
            ?: throw badRequest("면담 대상자를 찾을 수 없습니다.")
        val duplicated = interviews.existsByRequesterIdAndTargetIdAndPreferredAtAndStatus(
            me.id, target.id, req.preferredAt, InterviewStatus.PENDING,
        )
        if (duplicated || interviews.hasApprovedAt(me.id, req.preferredAt) || interviews.hasApprovedAt(target.id, req.preferredAt)) {
            throw conflict("같은 시간에 이미 면담이 있습니다.")
        }
        val saved = interviews.save(
            Interview(companyId = me.companyId, requesterId = me.id, targetId = target.id, preferredAt = req.preferredAt, reason = req.reason),
        )
        notify(me, saved.targetId, "면담 신청이 도착했습니다.", saved)
        return ApiResponse(InterviewResponse(dto(me, saved)))
    }

    /** INT-002 */
    @GetMapping
    fun list(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam(required = false) direction: Direction?,
        @RequestParam(required = false) status: InterviewStatus?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<Paged<InterviewDto>> {
        if (direction == Direction.ALL && me.role == Role.NEWBIE) throw forbidden()
        val spec = Specification<Interview> { root, _, cb ->
            val ps = mutableListOf<Predicate>(cb.equal(root.get<UUID>("companyId"), me.companyId))
            val mineAsRequester = cb.equal(root.get<UUID>("requesterId"), me.id)
            val mineAsTarget = cb.equal(root.get<UUID>("targetId"), me.id)
            when (direction) {
                Direction.RECEIVED -> ps += mineAsTarget
                Direction.SENT -> ps += mineAsRequester
                Direction.ALL -> Unit
                null -> ps += cb.or(mineAsRequester, mineAsTarget)
            }
            if (status != null) ps += cb.equal(root.get<InterviewStatus>("status"), status)
            if (from != null) ps += cb.greaterThanOrEqualTo(root.get("preferredAt"), from)
            if (to != null) ps += cb.lessThanOrEqualTo(root.get("preferredAt"), to)
            cb.and(*ps.toTypedArray())
        }
        val result = interviews.findAll(spec, pageOf(page, size, Sort.by(Sort.Direction.DESC, "preferredAt")))
        val names = names(result.content)
        return ApiResponse(result.toPaged { dto(me, it, names) })
    }

    /** INT-003: 당사자는 전체, HR·멘토는 현황 항목(당사자·일시·상태)만 본다. */
    @GetMapping("/{interviewId}")
    fun get(@AuthenticationPrincipal me: AuthUser, @PathVariable interviewId: UUID): ApiResponse<InterviewResponse> {
        val interview = interviews.findByIdAndCompanyId(interviewId, me.companyId) ?: throw notFound()
        if (!interview.involves(me.id) && me.role == Role.NEWBIE) throw forbidden()
        return ApiResponse(InterviewResponse(dto(me, interview)))
    }

    /** INT-004: 신청을 받은 본인만 승인한다. */
    @PostMapping("/{interviewId}/approve")
    fun approve(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable interviewId: UUID,
        @RequestBody(required = false) req: InterviewApproveRequest?,
    ): ApiResponse<InterviewResponse> {
        val interview = received(me, interviewId)
        val confirmedAt = req?.confirmedAt ?: interview.preferredAt
        if (interviews.hasApprovedAt(me.id, confirmedAt) || interviews.hasApprovedAt(interview.requesterId, confirmedAt)) {
            throw conflict("같은 시간에 이미 확정된 면담이 있습니다.")
        }
        interview.status = InterviewStatus.APPROVED
        interview.confirmedAt = confirmedAt
        interviews.saveAndFlush(interview)
        notify(me, interview.requesterId, "면담 신청이 승인되었습니다.", interview)
        return ApiResponse(InterviewResponse(dto(me, interview)))
    }

    /** INT-005: 신청을 받은 본인만, 사유와 함께 거절한다. */
    @PostMapping("/{interviewId}/reject")
    fun reject(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable interviewId: UUID,
        @Valid @RequestBody req: InterviewRejectRequest,
    ): ApiResponse<InterviewResponse> {
        val interview = received(me, interviewId)
        interview.status = InterviewStatus.REJECTED
        interview.rejectReason = req.reason
        interviews.saveAndFlush(interview)
        notify(me, interview.requesterId, "면담 신청이 거절되었습니다.", interview, body = req.reason)
        return ApiResponse(InterviewResponse(dto(me, interview)))
    }

    private fun received(me: AuthUser, id: UUID): Interview {
        val interview = interviews.findByIdAndCompanyId(id, me.companyId) ?: throw notFound()
        if (interview.targetId != me.id) throw forbidden("신청을 받은 본인만 처리할 수 있습니다.")
        if (interview.status != InterviewStatus.PENDING) throw conflict("이미 처리된 신청입니다.")
        return interview
    }

    private fun notify(me: AuthUser, recipientId: UUID, title: String, interview: Interview, body: String? = null) {
        events.publishEvent(
            NotifyEvent(
                companyId = me.companyId, actorId = me.id, recipientIds = listOf(recipientId),
                category = NotificationCategory.INTERVIEW, mandatory = false, title = title, body = body,
                linkType = "interview", linkId = interview.id,
            ),
        )
    }

    private fun names(list: List<Interview>): Map<UUID, String> =
        directory.usersById(list.flatMap { listOf(it.requesterId, it.targetId) }).mapValues { it.value.name }

    private fun dto(me: AuthUser, i: Interview, names: Map<UUID, String> = names(listOf(i))): InterviewDto {
        val party = i.involves(me.id)
        return InterviewDto(
            id = i.id,
            requester = PersonRef(i.requesterId, names[i.requesterId] ?: ""),
            target = PersonRef(i.targetId, names[i.targetId] ?: ""),
            preferredAt = i.preferredAt, confirmedAt = i.confirmedAt, status = i.status,
            reason = if (party) i.reason else null,
            rejectReason = if (party) i.rejectReason else null,
            createdAt = i.createdAt,
        )
    }
}
