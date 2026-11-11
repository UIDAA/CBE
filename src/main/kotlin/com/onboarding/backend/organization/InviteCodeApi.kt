package com.onboarding.backend.organization

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.Paged
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.badRequest
import com.onboarding.backend.common.conflict
import com.onboarding.backend.common.notFound
import com.onboarding.backend.common.toPaged
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

data class InviteCodeCreateRequest(val role: Role, val expiresAt: Instant)
data class InviteCodeCreatedResponse(val inviteCode: String, val expiresAt: Instant)
data class InviteCodeDto(
    val id: UUID,
    val inviteCode: String,
    val role: Role,
    val status: InviteCodeStatus,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val createdAt: Instant,
)

@RestController
@RequestMapping("/api/v1/invite-codes")
@Transactional
class InviteCodeController(
    private val codes: InviteCodeRepository,
    private val policy: OrganizationPolicyPort,
) {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** INV-001 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(@AuthenticationPrincipal me: AuthUser, @RequestBody req: InviteCodeCreateRequest): ApiResponse<InviteCodeCreatedResponse> {
        me.require(Role.HR)
        if (req.role == Role.HR) throw badRequest("초대 코드는 멘토·신입 역할로만 발급할 수 있습니다.")
        if (!req.expiresAt.isAfter(Instant.now())) throw badRequest("만료일은 현재 이후여야 합니다.")
        if (!policy.canIssueInvite(me.companyId, req.role)) throw conflict("요금제 인원 한도를 초과했습니다.")
        var code: String
        do {
            code = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        } while (codes.findByCode(code) != null)
        val saved = codes.save(
            InviteCode(companyId = me.companyId, code = code, role = req.role, expiresAt = req.expiresAt, createdBy = me.id),
        )
        return ApiResponse(InviteCodeCreatedResponse(saved.code, saved.expiresAt))
    }

    /** INV-002 */
    @GetMapping
    fun list(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam(required = false) status: InviteCodeStatus?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<Paged<InviteCodeDto>> {
        me.require(Role.HR)
        val now = Instant.now()
        val items = codes.findAllByCompanyIdOrderByCreatedAtDesc(me.companyId)
            .map { InviteCodeDto(it.id, it.code, it.role, it.status(now), it.expiresAt, it.usedAt, it.createdAt) }
            .filter { status == null || it.status == status }
        return ApiResponse(items.toPaged(page, size))
    }

    /** INV-003 */
    @DeleteMapping("/{inviteCodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal me: AuthUser, @PathVariable inviteCodeId: UUID) {
        me.require(Role.HR)
        val code = codes.findByIdAndCompanyId(inviteCodeId, me.companyId) ?: throw notFound()
        if (code.usedAt != null) throw conflict("이미 사용된 코드입니다.")
        if (code.revokedAt == null) code.revokedAt = Instant.now()
    }
}
