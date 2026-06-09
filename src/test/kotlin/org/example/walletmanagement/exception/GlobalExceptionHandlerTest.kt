package org.example.walletmanagement.exception

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.http.HttpStatus
import org.springframework.validation.BindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

@ExtendWith(MockitoExtension::class)
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Mock
    private lateinit var request: HttpServletRequest

    @Test
    fun `handleNotFound maps to 404 with message and path`() {
        `when`(request.requestURI).thenReturn(PATH)

        val response = handler.handleNotFound(ResourceNotFoundException("Wallet not found"), request)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(404, response.body!!.status)
        assertEquals("Wallet not found", response.body!!.message)
        assertEquals(PATH, response.body!!.path)
    }

    @Test
    fun `handleInsufficientFunds maps to 422`() {
        `when`(request.requestURI).thenReturn(PATH)

        val response = handler.handleInsufficientFunds(InsufficientFundsException("Insufficient funds"), request)

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals(422, response.body!!.status)
        assertEquals("Insufficient funds", response.body!!.message)
    }

    @Test
    fun `handleIdempotencyConflict maps to 409`() {
        `when`(request.requestURI).thenReturn(PATH)

        val response = handler.handleIdempotencyConflict(IdempotencyConflictException("still processing"), request)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(409, response.body!!.status)
        assertEquals("still processing", response.body!!.message)
    }

    @Test
    fun `handleDuplicateIdempotencyKey maps to 422`() {
        `when`(request.requestURI).thenReturn(PATH)

        val response = handler.handleDuplicateIdempotencyKey(DuplicateIdempotencyKeyException("reused key"), request)

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals(422, response.body!!.status)
        assertEquals("reused key", response.body!!.message)
    }

    @Test
    fun `handleWalletAlreadyExists maps to 409`() {
        `when`(request.requestURI).thenReturn(PATH)

        val response = handler.handleWalletAlreadyExists(WalletAlreadyExistsException("wallet exists"), request)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(409, response.body!!.status)
        assertEquals("wallet exists", response.body!!.message)
    }

    @Test
    fun `handleValidation maps to 400 and joins field errors`() {
        `when`(request.requestURI).thenReturn(PATH)
        val ex = mock(MethodArgumentNotValidException::class.java)
        val bindingResult = mock(BindingResult::class.java)
        `when`(ex.bindingResult).thenReturn(bindingResult)
        `when`(bindingResult.fieldErrors).thenReturn(
            listOf(
                FieldError("depositRequest", "amount", "must not be null"),
                FieldError("depositRequest", "amount", "must be positive")
            )
        )

        val response = handler.handleValidation(ex, request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals(400, response.body!!.status)
        assertEquals("amount: must not be null; amount: must be positive", response.body!!.message)
        assertEquals(PATH, response.body!!.path)
    }

    @Test
    fun `handleGeneral maps to 500 with a generic message that does not leak details`() {
        `when`(request.requestURI).thenReturn(PATH)

        val response = handler.handleGeneral(RuntimeException("NullPointer at line 42"), request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals(500, response.body!!.status)
        assertEquals("An unexpected error occurred", response.body!!.message)
        assertEquals(PATH, response.body!!.path)
    }

    companion object {
        private const val PATH = "/wallets/abc/deposit"
    }
}