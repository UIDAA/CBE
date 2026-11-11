package com.onboarding.backend.todo

import com.onboarding.backend.common.ApiResponse
import com.onboarding.backend.common.AuthUser
import com.onboarding.backend.common.Paged
import com.onboarding.backend.common.forbidden
import com.onboarding.backend.common.notFound
import com.onboarding.backend.common.pageOf
import com.onboarding.backend.common.toPaged
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.criteria.Predicate
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "todos")
class Todo(
    @Id val id: UUID = UUID.randomUUID(),
    val companyId: UUID,
    val ownerId: UUID,
    var title: String,
    var dueDate: LocalDate? = null,
    var reminderAt: Instant? = null,
    var memo: String? = null,
    var completed: Boolean = false,
    val createdAt: Instant = Instant.now(),
)

interface TodoRepository : JpaRepository<Todo, UUID>, JpaSpecificationExecutor<Todo> {
    fun findByIdAndCompanyId(id: UUID, companyId: UUID): Todo?
}

data class TodoCreateRequest(
    @field:NotBlank @field:Size(max = 50) val title: String,
    val dueDate: LocalDate? = null,
    val reminderAt: Instant? = null,
    @field:Size(max = 2000) val memo: String? = null,
)

data class TodoUpdateRequest(
    @field:Size(min = 1, max = 50) val title: String? = null,
    val dueDate: LocalDate? = null,
    val reminderAt: Instant? = null,
    @field:Size(max = 2000) val memo: String? = null,
    val completed: Boolean? = null,
)

data class TodoDto(
    val id: UUID,
    val title: String,
    val dueDate: LocalDate?,
    val reminderAt: Instant?,
    val memo: String?,
    val completed: Boolean,
    val createdAt: Instant,
)
data class TodoResponse(val todo: TodoDto)

@RestController
@RequestMapping("/api/v1/todos")
@Transactional
class TodoController(private val todos: TodoRepository) {

    /** TODO-001: 본인 투두만 조회한다. */
    @GetMapping
    fun list(
        @AuthenticationPrincipal me: AuthUser,
        @RequestParam("date_from", required = false) dateFrom: LocalDate?,
        @RequestParam("date_to", required = false) dateTo: LocalDate?,
        @RequestParam(required = false) completed: Boolean?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<Paged<TodoDto>> {
        val spec = Specification<Todo> { root, _, cb ->
            val ps = mutableListOf<Predicate>(cb.equal(root.get<UUID>("ownerId"), me.id))
            if (dateFrom != null) ps += cb.greaterThanOrEqualTo(root.get("dueDate"), dateFrom)
            if (dateTo != null) ps += cb.lessThanOrEqualTo(root.get("dueDate"), dateTo)
            if (completed != null) ps += cb.equal(root.get<Boolean>("completed"), completed)
            cb.and(*ps.toTypedArray())
        }
        return ApiResponse(todos.findAll(spec, pageOf(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))).toPaged(::dto))
    }

    /** TODO-002 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal me: AuthUser, @Valid @RequestBody req: TodoCreateRequest): ApiResponse<TodoResponse> {
        val saved = todos.save(
            Todo(
                companyId = me.companyId, ownerId = me.id, title = req.title.trim(),
                dueDate = req.dueDate, reminderAt = req.reminderAt, memo = req.memo,
            ),
        )
        return ApiResponse(TodoResponse(dto(saved)))
    }

    /** TODO-003 */
    @PatchMapping("/{todoId}")
    fun update(
        @AuthenticationPrincipal me: AuthUser,
        @PathVariable todoId: UUID,
        @Valid @RequestBody req: TodoUpdateRequest,
    ): ApiResponse<TodoResponse> {
        val todo = owned(me, todoId)
        req.title?.let { todo.title = it.trim() }
        req.dueDate?.let { todo.dueDate = it }
        req.reminderAt?.let { todo.reminderAt = it }
        req.memo?.let { todo.memo = it }
        req.completed?.let { todo.completed = it }
        return ApiResponse(TodoResponse(dto(todo)))
    }

    /** TODO-004 */
    @DeleteMapping("/{todoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal me: AuthUser, @PathVariable todoId: UUID) {
        todos.delete(owned(me, todoId))
    }

    private fun owned(me: AuthUser, id: UUID): Todo {
        val todo = todos.findByIdAndCompanyId(id, me.companyId) ?: throw notFound()
        if (todo.ownerId != me.id) throw forbidden()
        return todo
    }

    private fun dto(t: Todo) = TodoDto(t.id, t.title, t.dueDate, t.reminderAt, t.memo, t.completed, t.createdAt)
}
