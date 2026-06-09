package org.example.walletmanagement.exception

import jakarta.servlet.http.HttpServletRequest
import org.example.walletmanagement.dto.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleNotFound(ex: ResourceNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                message = ex.message ?: "Resource not found",
                path = request.requestURI
            )
        )

    @ExceptionHandler(InsufficientFundsException::class)
    fun handleInsufficientFunds(ex: InsufficientFundsException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponse(
                status = HttpStatus.UNPROCESSABLE_ENTITY.value(),
                message = ex.message ?: "Insufficient funds",
                path = request.requestURI
            )
        )

    @ExceptionHandler(IdempotencyConflictException::class)
    fun handleIdempotencyConflict(ex: IdempotencyConflictException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                message = ex.message ?: "Request already in progress",
                path = request.requestURI
            )
        )

    @ExceptionHandler(DuplicateIdempotencyKeyException::class)
    fun handleDuplicateIdempotencyKey(ex: DuplicateIdempotencyKeyException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponse(
                status = HttpStatus.UNPROCESSABLE_ENTITY.value(),
                message = ex.message ?: "Idempotency key reused with different parameters",
                path = request.requestURI
            )
        )

    @ExceptionHandler(WalletAlreadyExistsException::class)
    fun handleWalletAlreadyExists(ex: WalletAlreadyExistsException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                message = ex.message ?: "Wallet already exists",
                path = request.requestURI
            )
        )
    }


    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        println("Validation failed for request to ${request.requestURI}: $message")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                message = message,
                path = request.requestURI
            )
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(ex: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                message = "An unexpected error occurred",
                path = request.requestURI
            )
        )
}
