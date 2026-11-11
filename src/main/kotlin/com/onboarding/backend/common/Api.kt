package com.onboarding.backend.common

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import java.util.UUID

/** 모든 성공 응답의 공통 봉투. 명세서의 "응답 data 필드"가 data 아래에 들어간다. */
data class ApiResponse<T>(val data: T)

data class Pagination(val page: Int, val size: Int, val totalElements: Long, val totalPages: Int)

data class Paged<T>(val items: List<T>, val pagination: Pagination)

/** page는 1부터 시작한다. */
fun pageOf(page: Int, size: Int, sort: Sort = Sort.unsorted()): PageRequest {
    if (page < 1 || size !in 1..100) throw badRequest("page는 1 이상, size는 1~100이어야 합니다.")
    return PageRequest.of(page - 1, size, sort)
}

fun <E : Any, T> Page<E>.toPaged(mapper: (E) -> T): Paged<T> =
    Paged(content.map(mapper), Pagination(number + 1, size, totalElements, totalPages))

fun <T> List<T>.toPaged(page: Int, size: Int): Paged<T> {
    pageOf(page, size)
    val from = minOf((page - 1) * size, this.size)
    val items = subList(from, minOf(from + size, this.size))
    return Paged(items, Pagination(page, size, this.size.toLong(), (this.size + size - 1) / size))
}

class ApiException(val status: HttpStatus, override val message: String) : RuntimeException(message)

fun badRequest(message: String) = ApiException(HttpStatus.BAD_REQUEST, message)
fun unauthorized(message: String) = ApiException(HttpStatus.UNAUTHORIZED, message)
fun forbidden(message: String = "권한이 없습니다.") = ApiException(HttpStatus.FORBIDDEN, message)
fun notFound(message: String = "대상을 찾을 수 없습니다.") = ApiException(HttpStatus.NOT_FOUND, message)
fun conflict(message: String) = ApiException(HttpStatus.CONFLICT, message)

enum class Role { HR, MENTOR, NEWBIE }

/** 인증된 요청의 주체. 매 요청마다 DB의 현재 역할·회사로 만들어진다. */
data class AuthUser(val id: UUID, val companyId: UUID, val role: Role) {
    fun require(vararg roles: Role) {
        if (role !in roles) throw forbidden()
    }
}
