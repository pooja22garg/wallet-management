package org.example.walletmanagement.controller

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
import java.util.*

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WalletControllerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var userId: UUID

    @BeforeEach
    fun setup() {
        userId = UUID.randomUUID()
    }

    // ─── GET /wallets/{userId} ────────────────────────────────────────────────

    @Test
    fun `GET wallet returns 404 when wallet does not exist`() {
        mockMvc.get("/wallets/$userId")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
                jsonPath("$.message") { exists() }
            }
    }


    @Test
    fun `GET wallet returns 200 with balance after a deposit`() {
        createWallet(userId)
        deposit(userId, "100.00")

        mockMvc.get("/wallets/$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.userId") { value(userId.toString()) }
                jsonPath("$.balance") { value(100.0) }
            }
    }


    // ─── POST /wallets/{userId}/deposit ───────────────────────────────────────
    @Test
    fun `POST deposit to wallet returns 404 when wallet does not exist`() {
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "50.00"}"""
            header("Idempotency-Key", "key-${UUID.randomUUID()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
            jsonPath("$.message") { prefix("Wallet not found for") }
        }
    }

    @Test
    fun `POST deposit fails if amount contains greater than 2 values after decimal point`() {
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "50.00000000"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST deposit accumulates balance across multiple deposits`() {
        createWallet(userId)
        deposit(userId, "100.00")
        deposit(userId, "50.00")

        mockMvc.get("/wallets/$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.balance") { value(150.0) }
            }
    }

    @Test
    fun `POST deposit returns 400 when amount is missing`() {
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.status") { value(400) }
        }
    }

    @Test
    fun `POST deposit returns 400 when amount is zero`() {
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "0"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST deposit returns 400 when amount is negative`() {
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "-10.00"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST deposit is idempotent when same Idempotency-Key is reused`() {
        createWallet(userId)
        val key = "key-${UUID.randomUUID()}"

        val first = mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "30.00"}"""
            header("Idempotency-Key", key)
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val second = mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "30.00"}"""
            header("Idempotency-Key", key)
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        // Both calls return the same transactionId
        assert(first == second)

        // Balance should only reflect one deposit
        mockMvc.get("/wallets/$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.balance") { value(30.0) }
            }
    }

    // ─── POST /wallets/{userId}/trade ─────────────────────────────────────────

    @Test
    fun `POST trade returns 201 and debits balance`() {
        createWallet(userId)
        deposit(userId, "200.00")

        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "80.00"}"""
            header("Idempotency-Key", "trade-${UUID.randomUUID()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("SUCCESS") }
            jsonPath("$.amount") { value(80.0) }
        }

        mockMvc.get("/wallets/$userId")
            .andExpect {
                status { isOk() }
                jsonPath("$.balance") { value(120.0) }
            }
    }

    @Test
    fun `POST trade returns 404 when wallet does not exist`() {
        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "10.00"}"""
            header("Idempotency-Key", "trade-${UUID.randomUUID()}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
        }
    }

    @Test
    fun `POST trade returns 422 when balance is insufficient`() {
        createWallet(userId)
        deposit(userId, "10.00")

        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "50.00"}"""
            header("Idempotency-Key", "trade-${UUID.randomUUID()}")
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.status") { value(422) }
            jsonPath("$.message") { exists() }
        }
    }

    @Test
    fun `POST trade with exact balance succeeds`() {
        createWallet(userId)
        deposit(userId, "100.00")

        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "100.00"}"""
            header("Idempotency-Key", "trade-${UUID.randomUUID()}")
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("SUCCESS") }
        }

        mockMvc.get("/wallets/$userId")
            .andExpect {
                jsonPath("$.balance") { value(0.0) }
            }
    }

    @Test
    fun `POST trade returns 400 when amount is missing`() {
        mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST trade is idempotent when same Idempotency-Key is reused`() {
        createWallet(userId)
        deposit(userId, "500.00")

        val key = "trade-key-${UUID.randomUUID()}"

        val first = mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "100.00"}"""
            header("Idempotency-Key", key)
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        val second = mockMvc.post("/wallets/$userId/trade") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "100.00"}"""
            header("Idempotency-Key", key)
        }.andExpect { status { isCreated() } }
            .andReturn().response.contentAsString

        assert(first == second)

        mockMvc.get("/wallets/$userId")
            .andExpect {
                jsonPath("$.balance") { value(400.0) }
            }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun createWallet(userId: UUID) {
        mockMvc.post("/wallets/$userId") {
            contentType = MediaType.APPLICATION_JSON
        }.andExpect { status { isCreated() } }
    }

    private fun deposit(userId: UUID, amount: String) {
        mockMvc.post("/wallets/$userId/deposit") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount": "$amount"}"""
            header("Idempotency-Key", "seed-${UUID.randomUUID()}")
        }.andExpect { status { isCreated() } }
    }
}
