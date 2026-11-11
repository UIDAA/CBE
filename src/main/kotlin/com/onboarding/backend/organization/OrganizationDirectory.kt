package com.onboarding.backend.organization

import com.onboarding.backend.common.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 다른 모듈이 조직 정보를 읽을 때 쓰는 조회 전용 창구. */
@Service
@Transactional(readOnly = true)
class OrganizationDirectory(
    private val users: UserRepository,
    private val departments: DepartmentRepository,
    private val assignments: MentorAssignmentRepository,
    private val companies: CompanyRepository,
) {
    fun company(companyId: UUID): Company = companies.findById(companyId).orElseThrow()

    fun user(companyId: UUID, userId: UUID): User? = users.findByIdAndCompanyId(userId, companyId)

    fun usersById(ids: Collection<UUID>): Map<UUID, User> = users.findAllById(ids.toSet()).associateBy { it.id }

    fun activeUserIds(companyId: UUID): List<UUID> =
        users.findAllByCompanyIdAndStatus(companyId, UserStatus.ACTIVE).map { it.id }

    fun activeNewbies(companyId: UUID): List<User> =
        users.findAllByCompanyIdAndRoleAndStatusOrderByName(companyId, Role.NEWBIE, UserStatus.ACTIVE)

    fun departmentNames(companyId: UUID): Map<UUID, String> =
        departments.findAllByCompanyIdOrderByName(companyId).associate { it.id to it.name }

    fun department(companyId: UUID, departmentId: UUID): Department? =
        departments.findByIdAndCompanyId(departmentId, companyId)

    /** 신입 ID -> 담당 멘토 ID */
    fun mentorByNewbie(companyId: UUID): Map<UUID, UUID> =
        assignments.findAllByCompanyId(companyId).associate { it.newbieId to it.mentorId }

    fun menteeIds(mentorId: UUID): Set<UUID> = assignments.findAllByMentorId(mentorId).map { it.newbieId }.toSet()
}
