package com.onboarding.backend.account

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.badRequest
import com.onboarding.backend.common.forbidden
import com.onboarding.backend.common.unauthorized
import com.onboarding.backend.organization.CompanyRepository
import com.onboarding.backend.organization.DepartmentDto
import com.onboarding.backend.organization.DepartmentRepository
import com.onboarding.backend.organization.User
import com.onboarding.backend.organization.UserRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ProfileUpdateRequest(
    val name: String? = null,
    val position: String? = null,
    val departmentId: UUID? = null,
    @field:Pattern(regexp = "[0-9-]*") val phone: String? = null,
    // 아래는 본인이 바꿀 수 없는 필드. 값이 오면 403.
    val email: String? = null,
    val role: String? = null,
    val status: String? = null,
)

data class PasswordChangeRequest(
    @field:NotBlank val currentPassword: String,
    @field:Size(min = 8, max = 20) val newPassword: String,
)

data class ProfileDto(
    val id: UUID,
    val email: String,
    val name: String,
    val position: String?,
    val phone: String?,
    val department: DepartmentDto?,
)
data class MeResponse(val profile: ProfileDto, val role: Role, val workspace: WorkspaceSummary)
data class ProfileResponse(val profile: ProfileDto)

@RestController
@RequestMapping("/api/v1/users/me")
@Transactional
class MeController(
    private val users: UserRepository,
    private val companies: CompanyRepository,
    private val departments: DepartmentRepository,
    private val passwordEncoder: PasswordEncoder,
) {
    /** USER-001 */
    @GetMapping
    fun me(@AuthenticationPrincipal me: AuthUser): ApiResponse<MeResponse> {
        val user = users.findById(me.id).orElseThrow()
        val company = companies.findById(me.companyId).orElseThrow()
        return ApiResponse(MeResponse(profile(user), user.role, WorkspaceSummary(company.id, company.name, company.planType)))
    }

    /** USER-002 */
    @PatchMapping
    fun update(@AuthenticationPrincipal me: AuthUser, @Valid @RequestBody req: ProfileUpdateRequest): ApiResponse<ProfileResponse> {
        if (req.email != null || req.role != null || req.status != null) throw forbidden("변경할 수 없는 항목입니다.")
        val user = users.findById(me.id).orElseThrow()
        req.name?.let {
            if (it.isBlank()) throw badRequest("이름은 비울 수 없습니다.")
            user.name = it.trim()
        }
        req.position?.let { user.position = it }
        req.phone?.let { user.phone = it }
        req.departmentId?.let {
            departments.findByIdAndCompanyId(it, me.companyId) ?: throw badRequest("부서를 찾을 수 없습니다.")
            user.departmentId = it
        }
        return ApiResponse(ProfileResponse(profile(user)))
    }

    /** USER-003 */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@AuthenticationPrincipal me: AuthUser, @Valid @RequestBody req: PasswordChangeRequest) {
        val user = users.findById(me.id).orElseThrow()
        if (!passwordEncoder.matches(req.currentPassword, user.passwordHash)) throw unauthorized("현재 비밀번호가 일치하지 않습니다.")
        user.passwordHash = passwordEncoder.encode(req.newPassword)
    }

    private fun profile(u: User) = ProfileDto(
        u.id, u.email, u.name, u.position, u.phone,
        u.departmentId?.let { id -> departments.findByIdAndCompanyId(id, u.companyId)?.let { DepartmentDto(it.id, it.name) } },
    )
}
