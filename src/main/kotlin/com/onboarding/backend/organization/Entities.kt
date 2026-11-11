package com.onboarding.backend.organization

import com.onboarding.backend.common.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "companies")
class Company(
    @Id val id: UUID = UUID.randomUUID(),
    var name: String,
    var size: Int? = null,
    var industry: String? = null,
    var planType: String,
    val createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "departments")
class Department(
    @Id val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    var name: String,
)

enum class UserStatus { PENDING, ACTIVE, INACTIVE }

@Entity
@Table(name = "users")
class User(
    @Id val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val email: String,
    var passwordHash: String? = null,
    var name: String,
    @Column(name = "job_position") var position: String? = null,
    var phone: String? = null,
    @Enumerated(EnumType.STRING) var role: Role,
    var departmentId: UUID? = null,
    @Enumerated(EnumType.STRING) var status: UserStatus = UserStatus.ACTIVE,
    var deactivatedReason: String? = null,
    val createdAt: Instant = Instant.now(),
)

enum class InviteCodeStatus { ACTIVE, USED, EXPIRED, REVOKED }

@Entity
@Table(name = "invite_codes")
class InviteCode(
    @Id val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val code: String,
    @Enumerated(EnumType.STRING) val role: Role,
    val expiresAt: Instant,
    var usedAt: Instant? = null,
    var usedBy: UUID? = null,
    var revokedAt: Instant? = null,
    val createdBy: UUID,
    val createdAt: Instant = Instant.now(),
) {
    fun status(now: Instant = Instant.now()): InviteCodeStatus = when {
        usedAt != null -> InviteCodeStatus.USED
        revokedAt != null -> InviteCodeStatus.REVOKED
        !expiresAt.isAfter(now) -> InviteCodeStatus.EXPIRED
        else -> InviteCodeStatus.ACTIVE
    }
}

/** 신입 1명당 현재 담당 멘토 1명. */
@Entity
@Table(name = "mentor_assignments")
class MentorAssignment(
    @Id val newbieId: UUID,
    val companyId: UUID,
    var mentorId: UUID,
    var effectiveAt: Instant,
)

interface CompanyRepository : JpaRepository<Company, UUID>

interface DepartmentRepository : JpaRepository<Department, UUID> {
    fun findAllByCompanyIdOrderByName(companyId: UUID): List<Department>
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): Department?
    fun existsByCompanyIdAndName(companyId: UUID, name: String): Boolean
}

interface UserRepository : JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    fun findByEmail(email: String): User?
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): User?
    fun existsByCompanyIdAndDepartmentId(companyId: UUID, departmentId: UUID): Boolean
    fun countByCompanyIdAndRoleAndStatus(companyId: UUID, role: Role, status: UserStatus): Long
    fun findAllByCompanyIdAndStatus(companyId: UUID, status: UserStatus): List<User>
    fun findAllByCompanyIdAndRoleAndStatusOrderByName(companyId: UUID, role: Role, status: UserStatus): List<User>
}

interface InviteCodeRepository : JpaRepository<InviteCode, UUID> {
    fun findByCode(code: String): InviteCode?
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): InviteCode?
    fun findAllByCompanyIdOrderByCreatedAtDesc(companyId: UUID): List<InviteCode>
}

interface MentorAssignmentRepository : JpaRepository<MentorAssignment, UUID> {
    fun findAllByCompanyId(companyId: UUID): List<MentorAssignment>
    fun findAllByMentorId(mentorId: UUID): List<MentorAssignment>
    fun existsByMentorId(mentorId: UUID): Boolean
}
