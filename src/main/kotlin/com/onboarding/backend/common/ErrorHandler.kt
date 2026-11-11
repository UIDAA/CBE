package com.onboarding.backend.common

import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ErrorHandler {

    private fun problem(status: HttpStatus, detail: String?): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase))

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = problem(e.status, e.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다.")
        body.setProperty("errors", e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to it.defaultMessage) })
        return ResponseEntity.badRequest().body(body)
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
        ConstraintViolationException::class,
        HandlerMethodValidationException::class,
    )
    fun badInput(e: Exception) = problem(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다.")

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun optimisticLock(e: ObjectOptimisticLockingFailureException) =
        problem(HttpStatus.CONFLICT, "다른 변경이 먼저 반영되었습니다. 최신 상태를 다시 불러오세요.")
}
