package com.onboarding.backend.organization

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.Paged
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.badRequest
import com.onboarding.backend.common.conflict
import com.onboarding.backend.common.notFound
import com.onboarding.backend.common.pageOf
import com.onboarding.backend.common.toPaged
import jakarta.persistence.criteria.Predicate
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

enum class InviteMode { MANUAL, INVITE }

data class EmployeeCreateRequest(
    @field:NotBlank @field:Email val email: String,
    @field:NotBlank val name: String,
    val role: Role,
    val departmentId: UUID,
    val inviteMode: InviteMode,
)

data class EmployeeUpdateRequest(
    val departmentId: UUID? = null,
    val position: String? = null,
    val role: Role? = null,
    val status: UserStatus? = null,
)

data class EmployeeDeactivateRequest(@field:NotBlank val reason: String)
data class MentorAssignRequest(val mentorId: UUID, val effectiveAt: Instant)

data class PersonRef(val id: UUID, val name: String)
data class EmployeeDto(
    val id: UUID,
    val name: String,
    val position: String?,
    val role: Role,
    val status: UserStatus,
    val department: DepartmentDto?,
    val mentor: PersonRef?,
    val isMyMentor: Boolean,
    val isMyMentee: Boolean,
)
data class EmployeeResponse(val employee: EmployeeDto)
data class AssignmentDto(val newbieId: UUID, val mentorId: UUID, val effectiveAt: Instant)
data class AssignmentResponse(val assignment: AssignmentDto)

@RestController
@RequestMapping("/api/v1/employees")
@Transactional
class EmployeeController(
    private val users: UserRepository,
    private val departments: DepartmentRepository,
    private val assignments: MentorAssignmentRepository,
    private val companies: CompanyRepository,
    private val policy: OrganizationPolicyPort,
    private val mail: InviteMailSender,
) {
    /** EMP-001: 이름 가나다순, 기본 20개. HR이 아니면 활성 직원만 보인다. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam("department_id", required = false) departmentId: UUID?,
        @RequestParam(required = false) role: Role?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<Paged<EmployeeDto>> {
        val spec = Specification<User> { root, _, cb ->
            val ps = mutableListOf<Predicate>(cb.equal(root.get<UUID>("companyId"), me.companyId))
            if (me.role != Role.HR) ps += cb.equal(root.get<UserStatus>("status"), UserStatus.ACTIVE)
            if (departmentId != null) ps += cb.equal(root.get<UUID>("departmentId"), departmentId)
            if (role != null) ps += cb.equal(root.get<Role>("role"), role)
            if (!keyword.isNullOrBlank()) ps += cb.like(cb.lower(root.get("name")), "%" + keyword.trim().lowercase() + "%")
            cb.and(*ps.toTypedArray())
        }
        val result = users.findAll(spec, pageOf(page, size, Sort.by("name")))
        val view = View(me)
        return ApiResponse(result.toPaged { view.dto(it) })
    }

    /** EMP-002: 비밀번호 없는 PENDING 계정을 만든다. 당사자는 초대 코드 가입(AUTH-002)에서 같은 이메일로 계정을 활성화한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal me: AuthUser, @Valid @RequestBody req: EmployeeCreateRequest): ApiResponse<EmployeeResponse> {
        me.require(Role.HR)
        val email = req.email.trim().lowercase()
        if (users.findByEmail(email) != null) throw conflict("이미 등록된 이메일입니다.")
        departments.findByIdAndCompanyId(req.departmentId, me.companyId) ?: throw badRequest("부서를 찾을 수 없습니다.")
        val user = users.save(
            User(
                companyId = me.companyId, email = email, name = req.name.trim(), role = req.role,
                departmentId = req.departmentId, status = UserStatus.PENDING,
            ),
        )
        if (req.inviteMode == InviteMode.INVITE) {
            mail.sendInvite(email, user.name, companies.findById(me.companyId).orElseThrow().name)
        }
        return ApiResponse(EmployeeResponse(View(me).dto(user)))
    }

    /** EMP-003 */
    @PatchMapping("/{employeeId}")
    fun update(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable employeeId: UUID,
        @RequestBody req: EmployeeUpdateRequest,
    ): ApiResponse<EmployeeResponse> {
        me.require(Role.HR)
        val user = users.findByIdAndCompanyId(employeeId, me.companyId) ?: throw notFound()
        req.departmentId?.let {
            departments.findByIdAndCompanyId(it, me.companyId) ?: throw notFound("부서를 찾을 수 없습니다.")
            user.departmentId = it
        }
        req.position?.let { user.position = it }
        req.role?.let { newRole ->
            if (newRole != user.role) {
                if (user.role == Role.HR) ensureNotLastHr(user)
                val hasMatching = (user.role == Role.NEWBIE && assignments.existsById(user.id)) ||
                    (user.role == Role.MENTOR && assignments.existsByMentorId(user.id))
                if (hasMatching) throw conflict("신입–멘토 매칭이 남아 있어 역할을 바꿀 수 없습니다.")
                user.role = newRole
            }
        }
        req.status?.let { newStatus ->
            if (newStatus != user.status) {
                if (newStatus == UserStatus.PENDING) throw conflict("가입 대기 상태로 되돌릴 수 없습니다.")
                if (newStatus == UserStatus.ACTIVE && user.passwordHash == null) throw conflict("가입을 마치지 않은 계정입니다.")
                if (newStatus == UserStatus.INACTIVE && user.role == Role.HR) ensureNotLastHr(user)
                user.status = newStatus
                if (newStatus == UserStatus.ACTIVE) user.deactivatedReason = null
            }
        }
        return ApiResponse(EmployeeResponse(View(me).dto(user)))
    }

    /** EMP-004: 삭제하지 않고 비활성화한다. */
    @DeleteMapping("/{employeeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable employeeId: UUID,
        @Valid @RequestBody req: EmployeeDeactivateRequest,
    ) {
        me.require(Role.HR)
        val user = users.findByIdAndCompanyId(employeeId, me.companyId) ?: throw notFound()
        if (user.role == Role.HR && user.status == UserStatus.ACTIVE) ensureNotLastHr(user)
        if (policy.hasOngoingAssignment(me.companyId, user.id)) throw conflict("진행 중인 배정이 있습니다.")
        user.status = UserStatus.INACTIVE
        user.deactivatedReason = req.reason
    }

    /** EMP-005 */
    @PutMapping("/{newbieId}/mentor")
    fun assignMentor(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable newbieId: UUID,
        @RequestBody req: MentorAssignRequest,
    ): ApiResponse<AssignmentResponse> {
        me.require(Role.HR)
        val newbie = users.findByIdAndCompanyId(newbieId, me.companyId) ?: throw notFound()
        val mentor = users.findByIdAndCompanyId(req.mentorId, me.companyId) ?: throw notFound("멘토를 찾을 수 없습니다.")
        if (newbie.role != Role.NEWBIE) throw conflict("대상이 신입 역할이 아닙니다.")
        if (mentor.role != Role.MENTOR || mentor.status != UserStatus.ACTIVE) throw conflict("멘토 역할의 활성 직원이 아닙니다.")
        val assignment = assignments.findById(newbie.id).orElse(null)
            ?.apply { mentorId = mentor.id; effectiveAt = req.effectiveAt }
            ?: assignments.save(MentorAssignment(newbie.id, me.companyId, mentor.id, req.effectiveAt))
        return ApiResponse(AssignmentResponse(AssignmentDto(assignment.newbieId, assignment.mentorId, assignment.effectiveAt)))
    }

    private fun ensureNotLastHr(user: User) {
        if (user.status == UserStatus.ACTIVE &&
            users.countByCompanyIdAndRoleAndStatus(user.companyId, Role.HR, UserStatus.ACTIVE) <= 1
        ) throw conflict("마지막 HR 관리자는 변경할 수 없습니다.")
    }

    private inner class View(private val me: AuthUser) {
        private val deptNames = departments.findAllByCompanyIdOrderByName(me.companyId).associate { it.id to it.name }
        private val mentorByNewbie = assignments.findAllByCompanyId(me.companyId).associate { it.newbieId to it.mentorId }
        private val mentorNames = users.findAllById(mentorByNewbie.values.toSet()).associate { it.id to it.name }

        fun dto(u: User): EmployeeDto {
            val mentorId = mentorByNewbie[u.id]
            return EmployeeDto(
                id = u.id, name = u.name, position = u.position, role = u.role, status = u.status,
                department = u.departmentId?.let { id -> deptNames[id]?.let { DepartmentDto(id, it) } },
                mentor = mentorId?.let { PersonRef(it, mentorNames[it] ?: "") },
                isMyMentor = mentorByNewbie[me.id] == u.id,
                isMyMentee = mentorId == me.id,
            )
        }
    }
}
