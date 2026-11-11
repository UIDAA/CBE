package com.onboarding.backend.dashboard

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.notFound
import com.onboarding.backend.interview.InterviewRepository
import com.onboarding.backend.interview.InterviewStatus
import com.onboarding.backend.organization.OrganizationDirectory
import com.onboarding.backend.organization.PersonRef
import com.onboarding.backend.organization.User
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

// ---------------------------------------------------------------------------------------------
// 온보딩·과제·일지·퀴즈·설문·도움 요청 모듈이 채워 줄 지표. 대시보드는 이 인터페이스만 본다.
// 해당 모듈이 생기면 OnboardingMetricsPort 구현체를 제공하고 아래 EmptyOnboardingMetrics 를 지운다.
// ---------------------------------------------------------------------------------------------

/** 온보딩이 진행 중인 신입 1명의 지표. 값이 없으면 null. */
data class NewbieMetrics(
    val week: Int? = null,
    /** 승인된 태스크 수 ÷ 오늘까지 기한인 태스크 수 × 100 */
    val taskProgress: Double? = null,
    /** 제출한 일지 수 ÷ 열린 일지 수 × 100 */
    val journalRate: Double? = null,
    /** 제출한 퀴즈 점수 평균(100점 환산) */
    val quizAverage: Double? = null,
    /** 주간 설문 응답 점수 평균(5점 척도). 소규모 집계 제한에 걸리면 null */
    val adaptation: Double? = null,
    val remainingQuizCount: Int = 0,
    val recentWrongCount: Int = 0,
    /** 주의 상태 원인 목록(일지 미제출·낮은 퀴즈 점수·도움 요청 등) */
    val warnings: List<String> = emptyList(),
)

data class OnboardingActionCounts(
    val taskReviewPending: Long = 0,
    val journalFeedbackPending: Long = 0,
    val overdue: Long = 0,
    val helpRequests: Long = 0,
)

data class TodayItem(val type: String, val id: UUID, val title: String, val dueAt: String?)
data class NewbieToday(val currentStage: String, val todayItems: List<TodayItem>)

interface OnboardingMetricsPort {
    /** 온보딩 진행 중인 신입만 키로 포함한다. */
    fun newbieMetrics(companyId: UUID, newbieIds: Collection<UUID>, asOf: LocalDate): Map<UUID, NewbieMetrics>
    fun actionCounts(companyId: UUID, newbieIds: Collection<UUID>): OnboardingActionCounts

    /** 배정된 온보딩이 없으면 null. */
    fun newbieToday(companyId: UUID, newbieId: UUID, date: LocalDate): NewbieToday?
}

/** 임시 구현: 아직 연결된 온보딩 데이터가 없으므로 항상 비어 있다. */
@Component
class EmptyOnboardingMetrics : OnboardingMetricsPort {
    override fun newbieMetrics(companyId: UUID, newbieIds: Collection<UUID>, asOf: LocalDate) = emptyMap<UUID, NewbieMetrics>()
    override fun actionCounts(companyId: UUID, newbieIds: Collection<UUID>) = OnboardingActionCounts()
    override fun newbieToday(companyId: UUID, newbieId: UUID, date: LocalDate): NewbieToday? = null
}

// ---------------------------------------------------------------------------------------------

data class HrSummary(
    val activeNewbieCount: Int,
    val averageTaskProgress: Double?,
    val journalSubmissionRate: Double?,
    val averageQuizScore: Double?,
    val averageAdaptation: Double?,
)
data class NewbieStatus(
    val newbieId: UUID,
    val name: String,
    val department: String?,
    val mentor: PersonRef?,
    val week: Int?,
    val taskProgress: Double?,
    val adaptation: Double?,
)
data class HrDashboard(val summary: HrSummary, val newbieStatuses: List<NewbieStatus>)

data class ActionCounts(
    val taskReviewPending: Long,
    val journalFeedbackPending: Long,
    val overdue: Long,
    val interviewRequests: Long,
    val helpRequests: Long,
)
data class MenteeCard(
    val newbieId: UUID,
    val name: String,
    val week: Int?,
    val taskProgress: Double?,
    val adaptation: Double?,
    val remainingQuizCount: Int,
    val recentWrongCount: Int,
    val warnings: List<String>,
)
data class MentorDashboard(val actionCounts: ActionCounts, val menteeCards: List<MenteeCard>)

enum class Scope { MINE, COMPANY }

@RestController
@RequestMapping("/api/v1/dashboard")
@Transactional(readOnly = true)
class DashboardController(
    private val directory: OrganizationDirectory,
    private val metrics: OnboardingMetricsPort,
    private val interviews: InterviewRepository,
    @Value("\${app.time-zone}") timeZone: String,
) {
    private val zone = ZoneId.of(timeZone)

    /** DASH-001: 회사·부서별 온보딩 지표와 신입 현황 (HR·멘토) */
    @GetMapping("/hr")
    fun hr(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam("department_id", required = false) departmentId: UUID?,
        @RequestParam("as_of", required = false) asOf: LocalDate?,
    ): ApiResponse<HrDashboard> {
        me.require(Role.HR, Role.MENTOR)
        val newbies = directory.activeNewbies(me.companyId).filter { departmentId == null || it.departmentId == departmentId }
        val byNewbie = metrics.newbieMetrics(me.companyId, newbies.map { it.id }, asOf ?: LocalDate.now(zone))
        val deptNames = directory.departmentNames(me.companyId)
        val mentorByNewbie = directory.mentorByNewbie(me.companyId)
        val mentors = directory.usersById(mentorByNewbie.values)
        val values = byNewbie.values
        return ApiResponse(
            HrDashboard(
                HrSummary(
                    activeNewbieCount = byNewbie.size,
                    averageTaskProgress = values.mapNotNull { it.taskProgress }.averageOrNull(),
                    journalSubmissionRate = values.mapNotNull { it.journalRate }.averageOrNull(),
                    averageQuizScore = values.mapNotNull { it.quizAverage }.averageOrNull(),
                    averageAdaptation = values.mapNotNull { it.adaptation }.averageOrNull(),
                ),
                newbies.map { n ->
                    val m = byNewbie[n.id]
                    NewbieStatus(
                        n.id, n.name, n.departmentId?.let(deptNames::get),
                        mentorByNewbie[n.id]?.let { id -> mentors[id]?.let { PersonRef(id, it.name) } },
                        m?.week, m?.taskProgress, m?.adaptation,
                    )
                },
            ),
        )
    }

    /** DASH-002: 멘토 홈. scope=MINE(기본, 내 담당 신입) | COMPANY(회사 전체) */
    @GetMapping("/mentor")
    fun mentor(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam(defaultValue = "MINE") scope: Scope,
    ): ApiResponse<MentorDashboard> {
        me.require(Role.MENTOR)
        val all = directory.activeNewbies(me.companyId)
        val newbies: List<User> = if (scope == Scope.COMPANY) all else directory.menteeIds(me.id).let { ids -> all.filter { it.id in ids } }
        val ids = newbies.map { it.id }
        val byNewbie = metrics.newbieMetrics(me.companyId, ids, LocalDate.now(zone))
        val counts = metrics.actionCounts(me.companyId, ids)
        val interviewRequests = if (scope == Scope.COMPANY) {
            interviews.countByCompanyIdAndStatus(me.companyId, InterviewStatus.PENDING)
        } else {
            interviews.countByTargetIdAndStatus(me.id, InterviewStatus.PENDING)
        }
        return ApiResponse(
            MentorDashboard(
                ActionCounts(counts.taskReviewPending, counts.journalFeedbackPending, counts.overdue, interviewRequests, counts.helpRequests),
                newbies.map { n ->
                    val m = byNewbie[n.id] ?: NewbieMetrics()
                    MenteeCard(n.id, n.name, m.week, m.taskProgress, m.adaptation, m.remainingQuizCount, m.recentWrongCount, m.warnings)
                },
            ),
        )
    }

    /** DASH-003: 신입 홈. 배정된 여정이 없으면 404. */
    @GetMapping("/newbie")
    fun newbie(@AuthenticationPrincipal me: AuthUser, @RequestParam(required = false) date: LocalDate?): ApiResponse<NewbieToday> {
        me.require(Role.NEWBIE)
        val today = metrics.newbieToday(me.companyId, me.id, date ?: LocalDate.now(zone)) ?: throw notFound("배정된 여정이 없습니다.")
        return ApiResponse(today)
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
