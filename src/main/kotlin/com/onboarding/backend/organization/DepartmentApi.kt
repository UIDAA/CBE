package com.onboarding.backend.organization

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.Role
import com.onboarding.backend.common.conflict
import com.onboarding.backend.common.notFound
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class DepartmentRequest(@field:NotBlank @field:Size(max = 100) val name: String)
data class DepartmentDto(val id: UUID, val name: String)
data class DepartmentListResponse(val items: List<DepartmentDto>)
data class DepartmentResponse(val department: DepartmentDto)

@RestController
@RequestMapping("/api/v1/departments")
@Transactional
class DepartmentController(
    private val departments: DepartmentRepository,
    private val users: UserRepository,
) {
    /** DEPT-001 */
    @GetMapping
    fun list(@AuthenticationPrincipal me: AuthUser) = ApiResponse(
        DepartmentListResponse(departments.findAllByCompanyIdOrderByName(me.companyId).map { DepartmentDto(it.id, it.name) }),
    )

    /** DEPT-002 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal me: AuthUser, @Valid @RequestBody req: DepartmentRequest): ApiResponse<DepartmentResponse> {
        me.require(Role.HR)
        val name = req.name.trim()
        if (departments.existsByCompanyIdAndName(me.companyId, name)) throw conflict("이미 있는 부서명입니다.")
        val saved = departments.save(Department(companyId = me.companyId, name = name))
        return ApiResponse(DepartmentResponse(DepartmentDto(saved.id, saved.name)))
    }

    /** DEPT-003 */
    @PatchMapping("/{departmentId}")
    fun update(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable departmentId: UUID,
        @Valid @RequestBody req: DepartmentRequest,
    ): ApiResponse<DepartmentResponse> {
        me.require(Role.HR)
        val dept = departments.findByIdAndCompanyId(departmentId, me.companyId) ?: throw notFound()
        val name = req.name.trim()
        if (name != dept.name && departments.existsByCompanyIdAndName(me.companyId, name)) throw conflict("이미 있는 부서명입니다.")
        dept.name = name
        return ApiResponse(DepartmentResponse(DepartmentDto(dept.id, dept.name)))
    }

    /** DEPT-004 */
    @DeleteMapping("/{departmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal me: AuthUser, @PathVariable departmentId: UUID) {
        me.require(Role.HR)
        val dept = departments.findByIdAndCompanyId(departmentId, me.companyId) ?: throw notFound()
        if (users.existsByCompanyIdAndDepartmentId(me.companyId, dept.id)) throw conflict("소속 직원이 있는 부서는 삭제할 수 없습니다.")
        departments.delete(dept)
    }
}
