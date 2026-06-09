package org.example.walletmanagement.controller

import org.example.walletmanagement.dto.DepositRequest
import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.entity.IdempotencyStatus
import org.example.walletmanagement.repository.IdempotencyRepository
import org.example.walletmanagement.service.WalletService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Two ordered tests sharing the same user + key:
 *  1. the request fails with a 5xx → the idempotency record is stored as FAILED.
 *  2. the SAME request is sent again → it must be treated as a fresh request and succeed,
 *     exercising the FAILED branch in IdempotencyFilter.
 *
 * State carries across the two methods (no per-method context reset), so the FAILED record
 * created by test 1 is what test 2 reprocesses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WalletIdempotencyServerErrorIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var idempotencyRepository: IdempotencyRepository

    @MockitoBean private lateinit var walletService: WalletService

    @Test
    @Order(1)
    fun `5xx is stored as FAILED`() {
        Mockito.`when`(walletService.deposit(anyUuid(), anyReq(), anyStr()))
            .thenThrow(RuntimeException("boom"))

        mockMvc.post("/wallets/$USER_ID/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = REQUEST_BODY
            header("Idempotency-Key", KEY)
        }.andExpect { status { isInternalServerError() } }

        val record = idempotencyRepository.findByIdempotencyKeyAndUserId(KEY, USER_ID).get()
        assertEquals(IdempotencyStatus.FAILED, record.status)
        failedRecordId = record.id
    }

    @Test
    @Order(2)
    fun `duplicate request for a FAILED request should succeed`() {
        // A FAILED record for (KEY, USER_ID) already exists from test 1.
        Mockito.`when`(walletService.deposit(anyUuid(), anyReq(), anyStr()))
            .thenReturn(
                DepositResponse(
                    UUID.randomUUID(), USER_ID, USER_ID,
                    BigDecimal("10.00"), BigDecimal("20.00"), "SUCCESS", Instant.now()
                )
            )

        mockMvc.post("/wallets/$USER_ID/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = REQUEST_BODY
            header("Idempotency-Key", KEY)
        }.andExpect { status { isCreated() } }

        val record = idempotencyRepository.findByIdempotencyKeyAndUserId(KEY, USER_ID).get()
        assertEquals(IdempotencyStatus.COMPLETED, record.status)
        // the SAME row was updated in place, not replaced with a new one
        assertEquals(failedRecordId, record.id)
        assertEquals(1, idempotencyRepository.findAll().count { it.idempotencyKey == KEY && it.userId == USER_ID })
    }

    private fun anyUuid(): UUID = Mockito.any(UUID::class.java) ?: UUID.randomUUID()
    private fun anyReq(): DepositRequest = Mockito.any(DepositRequest::class.java) ?: DepositRequest(BigDecimal.ONE)
    private fun anyStr(): String = Mockito.any(String::class.java) ?: ""

    companion object {
        private val USER_ID: UUID = UUID.randomUUID()
        private const val KEY = "shared-failed-key"
        private const val REQUEST_BODY = """{"amount":"10.00"}"""
        private var failedRecordId: UUID? = null
    }
}
