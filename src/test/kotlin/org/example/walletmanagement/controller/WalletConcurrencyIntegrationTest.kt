package org.example.walletmanagement.controller

import org.example.walletmanagement.repository.WalletRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies that the PESSIMISTIC_WRITE lock on the wallet row (findByUserIdForUpdate inside
 * applyDeposit/applyTrade) serializes concurrent writers — no lost updates and no overdraft.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WalletConcurrencyIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var walletRepository: WalletRepository

    private lateinit var userId: UUID

    @BeforeEach
    fun setup() {
        userId = UUID.randomUUID()
    }

    @Test
    fun `concurrent deposits do not lose updates`() {
        createWallet()

        val statuses = runConcurrently(THREADS) { i ->
            depositStatus(amount = "10.00", key = "dep-$userId-$i")
        }

        // Every deposit committed (serialized, none lost)…
        assertTrue(statuses.all { it == 201 }, "expected all 201, got $statuses")
        // …and the balance is the exact sum.
        assertBalance(BigDecimal((THREADS * 10).toString()) + BigDecimal("0.00"))
    }

    @Test
    fun `concurrent trades never overdraw the wallet`() {
        createWallet()
        depositStatus(amount = "100.00", key = "seed-$userId")   // balance = 100

        // 8 concurrent trades of 20 against a balance of 100 → exactly 5 can succeed.
        val statuses = runConcurrently(THREADS) { i ->
            tradeStatus(amount = "20.00", key = "trade-$userId-$i")
        }

        assertEquals(5, statuses.count { it == 201 }, "exactly 5 trades should succeed")
        assertEquals(THREADS - 5, statuses.count { it == 422 }, "the rest must be rejected as insufficient funds")
        assertBalance(BigDecimal("0.00")) // never negative
    }

    @Test
    fun `concurrent wallet creation creates only one wallet`() {
        val statuses = runConcurrently(THREADS) {
            mockMvc.post("/wallets/$userId").andReturn().response.status
        }

        assertEquals(1, statuses.count { it == 201 }, "only one creation should win")
        assertTrue(walletRepository.findByUserId(userId).isPresent, "exactly one wallet row must exist")
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun createWallet() {
        mockMvc.post("/wallets/$userId").andExpect { /* 201 */ }
    }

    private fun depositStatus(amount: String, key: String): Int =
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"$amount"}"""
            header("Idempotency-Key", key)
        }.andReturn().response.status

    private fun tradeStatus(amount: String, key: String): Int =
        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":"$amount"}"""
            header("Idempotency-Key", key)
        }.andReturn().response.status

    private fun assertBalance(expected: BigDecimal) {
        mockMvc.get("/wallets/$userId").andExpect { jsonPath("$.balance") { value(expected.toDouble()) } }
    }

    /** Runs [task] on [n] threads released simultaneously to maximize contention. */
    private fun <T> runConcurrently(n: Int, task: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(n)
        try {
            val start = CountDownLatch(1)
            val futures = (0 until n).map { i ->
                pool.submit<T> {
                    start.await()
                    task(i)
                }
            }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val THREADS = 8
    }
}