package org.example.walletmanagement.controller

import org.example.walletmanagement.entity.IdempotencyEntity
import org.example.walletmanagement.entity.IdempotencyStatus
import org.example.walletmanagement.entity.LedgerLogEntity
import org.example.walletmanagement.entity.LedgerType
import org.example.walletmanagement.entity.OperationType
import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.example.walletmanagement.entity.TransactionStatus
import org.example.walletmanagement.entity.WalletEntity
import org.example.walletmanagement.repository.IdempotencyRepository
import org.example.walletmanagement.repository.LedgerLogRepository
import org.example.walletmanagement.repository.TransactionHistoryRepository
import org.example.walletmanagement.repository.WalletRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WalletIdempotencyIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var walletRepository: WalletRepository
    @Autowired private lateinit var transactionHistoryRepository: TransactionHistoryRepository
    @Autowired private lateinit var ledgerLogRepository: LedgerLogRepository
    @Autowired private lateinit var idempotencyRepository: IdempotencyRepository

    private lateinit var userId: UUID

    @BeforeEach
    fun setup() {
        userId = UUID.randomUUID()
    }

    // ─── header / dedup validation ──────────────────────────────────────────────

    @Test
    fun `deposit without Idempotency-Key returns 400`() {
        createWallet()
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"10.00"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Missing Idempotency-Key header") }
        }
    }

    @Test
    fun `same key replays the first response and applies the effect once`() {
        createWallet()
        val key = "k-${UUID.randomUUID()}"

        val first = deposit(key, "30.00").andReturn().response.contentAsString
        val second = deposit(key, "30.00").andReturn().response.contentAsString

        assertEquals(first, second)
        mockMvc.get("/wallets/$userId").andExpect { jsonPath("$.balance") { value(30.0) } }
        assertEquals(IdempotencyStatus.COMPLETED, idempotencyRepository.findByIdempotencyKeyAndUserId(key, userId).get().status)
    }

    @Test
    fun `same key with a different body returns 422`() {
        createWallet()
        val key = "k-${UUID.randomUUID()}"

        deposit(key, "30.00").andExpect { status { isCreated() } }

        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"99.00"}"""
            header("Idempotency-Key", key)
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.message") { value("Idempotency key '$key' was reused with different parameters") }
        }
        // only the first deposit took effect
        mockMvc.get("/wallets/$userId").andExpect { jsonPath("$.balance") { value(30.0) } }
    }

    @Test
    fun `same key reused on a different endpoint returns 422`() {
        createWallet()
        deposit(key = "shared", amount = "30.00").andExpect { status { isCreated() } }

        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"30.00"}"""
            header("Idempotency-Key", "shared")
        }.andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `the same key used by two different users is allowed and isolated`() {
        val userB = UUID.randomUUID()
        createWallet()                                   // user A (class userId)
        mockMvc.post("/wallets/$userB").andExpect { status { isCreated() } }
        val key = "shared-across-users"

        deposit(key, "10.00").andExpect { status { isCreated() } }      // A
        mockMvc.post("/wallets/$userB/deposit") {                       // B, same key
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"20.00"}"""
            header("Idempotency-Key", key)
        }.andExpect { status { isCreated() } }

        mockMvc.get("/wallets/$userId").andExpect { jsonPath("$.balance") { value(10.0) } }
        mockMvc.get("/wallets/$userB").andExpect { jsonPath("$.balance") { value(20.0) } }
    }

    // ─── deterministic failure replay ───────────────────────────────────────────

    @Test
    fun `insufficient-funds 422 is stored and replayed for the same key`() {
        createWallet() // balance 0
        val key = "k-${UUID.randomUUID()}"

        val first = trade(key, "50.00").andExpect { status { isUnprocessableEntity() } }
            .andReturn().response.contentAsString
        val second = trade(key, "50.00").andExpect { status { isUnprocessableEntity() } }
            .andReturn().response.contentAsString

        assertEquals(first, second)
    }

    @Test
    fun `a FAILED record is reprocessed on retry`() {
        createWallet()
        val key = "k-${UUID.randomUUID()}"
        val body = """{"amount":"25.00"}"""
        idempotencyRepository.save(
            IdempotencyEntity(
                idempotencyKey = key, userId = userId, endpoint = "deposit",
                requestHash = sha256(body), status = IdempotencyStatus.FAILED,
                responseBody = "prior error", responseStatus = 500
            )
        )

        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Idempotency-Key", key)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.amount") { value(25.0) }
        }
        mockMvc.get("/wallets/$userId").andExpect { jsonPath("$.balance") { value(25.0) } }
        assertEquals(IdempotencyStatus.COMPLETED, idempotencyRepository.findByIdempotencyKeyAndUserId(key, userId).get().status)
    }

    // ─── reconstruct fallback (committed effect, response never stored) ──────────

    @Test
    fun `stuck IN_PROGRESS is rebuilt from the committed SUCCESS transaction`() {
        val key = "k-${UUID.randomUUID()}"
        val body = """{"amount":"40.00"}"""

        // live wallet balance is deliberately different from balanceAfter to prove
        // reconstruct uses the ledger, not the current balance.
        val wallet = walletRepository.save(WalletEntity(userId = userId, balance = BigDecimal("999.00")))
        val txn = transactionHistoryRepository.save(
            TransactionHistoryEntity(
                wallet = wallet, idempotencyKey = key, operation = OperationType.DEPOSIT,
                status = TransactionStatus.SUCCESS, amount = BigDecimal("40.00")
            )
        )
        ledgerLogRepository.save(
            LedgerLogEntity(
                wallet = wallet, transaction = txn, type = LedgerType.CREDIT,
                amount = BigDecimal("40.00"), balanceBefore = BigDecimal("100.00")
            )
        )
        idempotencyRepository.save(
            IdempotencyEntity(
                idempotencyKey = key, userId = userId, endpoint = "deposit",
                requestHash = sha256(body), status = IdempotencyStatus.IN_PROGRESS
            )
        )

        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Idempotency-Key", key)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.transactionId") { value(txn.id.toString()) }
            jsonPath("$.amount") { value(40.0) }
            jsonPath("$.balanceAfter") { value(140.0) } // 100 (ledger) + 40, not 999
            jsonPath("$.status") { value("SUCCESS") }
        }

        assertEquals(IdempotencyStatus.COMPLETED, idempotencyRepository.findByIdempotencyKeyAndUserId(key, userId).get().status)
    }

    @Test
    fun `stuck IN_PROGRESS with nothing to rebuild returns 409`() {
        val key = "k-${UUID.randomUUID()}"
        val body = """{"amount":"40.00"}"""
        idempotencyRepository.save(
            IdempotencyEntity(
                idempotencyKey = key, userId = userId, endpoint = "deposit",
                requestHash = sha256(body), status = IdempotencyStatus.IN_PROGRESS
            )
        )

        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Idempotency-Key", key)
        }.andExpect { status { isConflict() } }
    }

    // ─── expiry ─────────────────────────────────────────────────────────────────

    @Test
    fun `expired record is re-initiated and the request is reprocessed`() {
        createWallet()
        val key = "k-${UUID.randomUUID()}"
        val body = """{"amount":"25.00"}"""
        idempotencyRepository.save(
            IdempotencyEntity(
                idempotencyKey = key, userId = userId, endpoint = "deposit",
                requestHash = sha256(body), status = IdempotencyStatus.COMPLETED,
                responseBody = "stale", responseStatus = 201,
                expiresAt = Instant.now().minusSeconds(60)
            )
        )

        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Idempotency-Key", key)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.amount") { value(25.0) }
        }
        mockMvc.get("/wallets/$userId").andExpect { jsonPath("$.balance") { value(25.0) } }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun createWallet() {
        mockMvc.post("/wallets/$userId").andExpect { status { isCreated() } }
    }

    private fun deposit(key: String, amount: String) =
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"$amount"}"""
            header("Idempotency-Key", key)
        }

    private fun trade(key: String, amount: String) =
        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"$amount"}"""
            header("Idempotency-Key", key)
        }

    private fun sha256(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
