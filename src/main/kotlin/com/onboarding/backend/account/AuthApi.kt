package com.onboarding.backend.account

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.badRequest
import com.onboarding.backend.common.conflict
import com.onboarding.backend.common.unauthorized
import com.onboarding.backend.organization.Company
import com.onboarding.backend.organization.CompanyRepository
import com.onboarding.backend.organization.DepartmentRepository
import com.onboarding.backend.organization.InviteCodeRepository
import com.onboarding.backend.organization.InviteCodeStatus
import com.onboarding.backend.organization.User
import com.onboarding.backend.organization.UserRepository
import com.onboarding.backend.organization.UserStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CompanySignupRequest(
    @field:NotBlank @field:Size(max = 30) val companyName: String,
    val companySize: Int? = null,
    val industry: String? = null,
    @field:Pattern(regexp = "FREE|PRO") val planType: String,
    @field:NotBlank @field:Email val email: String,
    @field:Size(min = 8, max = 20) val password: String,
    val passwordConfirm: String,
    @field:NotBlank val name: String,
)

data class ProfileInput(
    @field:NotBlank val name: String,
    @field:NotBlank val position: String,
    val departmentId: UUID,
    @field:Pattern(regexp = "[0-9-]*") val phone: String? = null,
)

data class InviteSignupRequest(
    @field:NotBlank val inviteCode: String,
    @field:NotBlank @field:Email val email: String,
    @field:Size(min = 8, max = 20) val password: String,
    val passwordConfirm: String,
    @field:Valid val profile: ProfileInput,
)

data class LoginRequest(@field:NotBlank val email: String, @field:NotBlank val password: String)
data class LogoutRequest(val refreshToken: String? = null)
data class RefreshRequest(@field:NotBlank val refreshToken: String)

data class UserSummary(val id: UUID, val email: String, val name: String)
data class WorkspaceSummary(val id: UUID, val name: String, val planType: String)
data class AuthResponse(val user: UserSummary, val role: Role, val workspace: WorkspaceSummary, val tokens: Tokens)
data class RefreshResponse(val accessToken: String, val expiresIn: Long)

@RestController
@RequestMapping("/api/v1/auth")
@Transactional
class AuthController(
    private val users: UserRepository,
    private val companies: CompanyRepository,
    private val departments: DepartmentRepository,
    private val inviteCodes: InviteCodeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokens: TokenService,
) {
    /** AUTH-001: 회사 워크스페이스 생성 + 첫 HR 계정 */
    @PostMapping("/signup/workspace")
    @ResponseStatus(HttpStatus.CREATED)
    fun signupWorkspace(@Valid @RequestBody req: CompanySignupRequest): ApiResponse<AuthResponse> {
        if (req.password != req.passwordConfirm) throw badRequest("비밀번호 확인이 일치하지 않습니다.")
        val email = req.email.trim().lowercase()
        if (users.findByEmail(email) != null) throw conflict("이미 가입한 이메일입니다.")
        val company = companies.save(
            Company(name = req.companyName.trim(), size = req.companySize, industry = req.industry, planType = req.planType),
        )
        val user = users.save(
            User(
                companyId = company.id, email = email, passwordHash = passwordEncoder.encode(req.password),
                name = req.name.trim(), role = Role.HR,
            ),
        )
        return ApiResponse(authResponse(user, company))
    }

    /** AUTH-002: 초대 코드 가입. 코드가 회사·역할을 정한다. */
    @PostMapping("/signup/invite")
    @ResponseStatus(HttpStatus.CREATED)
    fun signupInvite(@Valid @RequestBody req: InviteSignupRequest): ApiResponse<AuthResponse> {
        if (req.password != req.passwordConfirm) throw badRequest("비밀번호 확인이 일치하지 않습니다.")
        val code = inviteCodes.findByCode(req.inviteCode.trim()) ?: throw badRequest("초대 코드를 다시 확인해 주세요.")
        when (code.status()) {
            InviteCodeStatus.USED -> throw conflict("이미 사용된 초대 코드입니다.")
            InviteCodeStatus.EXPIRED, InviteCodeStatus.REVOKED -> throw badRequest("만료된 초대 코드입니다. 관리자에게 다시 요청해 주세요.")
            InviteCodeStatus.ACTIVE -> Unit
        }
        departments.findByIdAndCompanyId(req.profile.departmentId, code.companyId) ?: throw badRequest("부서를 찾을 수 없습니다.")
        val email = req.email.trim().lowercase()
        val existing = users.findByEmail(email)
        // HR이 EMP-002로 미리 등록해 둔 같은 회사의 가입 대기 계정이면 그 계정을 활성화한다.
        val user = if (existing != null) {
            if (existing.status != UserStatus.PENDING || existing.companyId != code.companyId) throw conflict("이미 가입한 이메일입니다.")
            existing.also { it.role = code.role; it.status = UserStatus.ACTIVE }
        } else {
            users.save(User(companyId = code.companyId, email = email, name = req.profile.name.trim(), role = code.role))
        }
        user.passwordHash = passwordEncoder.encode(req.password)
        user.name = req.profile.name.trim()
        user.position = req.profile.position
        user.departmentId = req.profile.departmentId
        user.phone = req.profile.phone
        code.usedAt = Instant.now()
        code.usedBy = user.id
        return ApiResponse(authResponse(user, companies.findById(code.companyId).orElseThrow()))
    }

    /** AUTH-003 */
    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): ApiResponse<AuthResponse> {
        val user = users.findByEmail(req.email.trim().lowercase())
        val hash = user?.passwordHash
        if (user == null || hash == null || user.status != UserStatus.ACTIVE || !passwordEncoder.matches(req.password, hash)) {
            throw unauthorized("이메일 또는 비밀번호를 확인해 주세요.")
        }
        return ApiResponse(authResponse(user, companies.findById(user.companyId).orElseThrow()))
    }

    /** AUTH-004: refresh_token을 주면 그 세션만, 없으면 내 모든 세션을 종료한다. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthenticationPrincipal me: AuthUser, @RequestBody(required = false) req: LogoutRequest?) {
        tokens.revoke(me.id, req?.refreshToken)
    }

    /** AUTH-005 */
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshRequest): ApiResponse<RefreshResponse> {
        val user = tokens.validRefreshOwner(req.refreshToken)?.let { users.findById(it).orElse(null) }
        if (user == null || user.status != UserStatus.ACTIVE) throw unauthorized("만료되었거나 폐기된 토큰입니다.")
        return ApiResponse(RefreshResponse(tokens.accessToken(user), tokens.accessTtlSeconds))
    }

    private fun authResponse(user: User, company: Company) = AuthResponse(
        UserSummary(user.id, user.email, user.name), user.role,
        WorkspaceSummary(company.id, company.name, company.planType), tokens.issue(user),
    )
}
