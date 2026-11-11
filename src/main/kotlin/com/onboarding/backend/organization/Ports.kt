package com.onboarding.backend.organization

import com.onboarding.backend.common.Role
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 조직 모듈이 다른 담당자 모듈에 묻는 정책. 지금은 임시 구현이며,
 * 요금제(PLAN)·온보딩 배정(ONB) 모듈이 생기면 그쪽에서 구현체를 제공하고 아래 임시 구현을 지운다.
 */
interface OrganizationPolicyPort {
    /** INV-001: 요금제 인원 한도 안에서 초대 코드를 더 발급할 수 있는가. */
    fun canIssueInvite(companyId: UUID, role: Role): Boolean

    /** EMP-004: 해당 직원에게 진행 중인 온보딩 배정이 있는가. */
    fun hasOngoingAssignment(companyId: UUID, userId: UUID): Boolean
}

@Component
class TemporaryOrganizationPolicy : OrganizationPolicyPort {
    override fun canIssueInvite(companyId: UUID, role: Role) = true
    override fun hasOngoingAssignment(companyId: UUID, userId: UUID) = false
}

/** EMP-002 초대 발송. 지금은 로그만 남긴다(메일 연동 시 교체). */
interface InviteMailSender {
    fun sendInvite(email: String, name: String, companyName: String)
}

@Component
class LoggingInviteMailSender : InviteMailSender {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun sendInvite(email: String, name: String, companyName: String) {
        log.info("초대 메일 발송 요청: company={}", companyName)
    }
}
