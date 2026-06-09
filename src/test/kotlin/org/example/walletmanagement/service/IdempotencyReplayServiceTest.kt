package org.example.walletmanagement.service

import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.dto.TradeResponse
import org.example.walletmanagement.entity.LedgerLogEntity
import org.example.walletmanagement.entity.LedgerType
import org.example.walletmanagement.entity.OperationType
import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.example.walletmanagement.entity.TransactionStatus
import org.example.walletmanagement.entity.WalletEntity
import org.example.walletmanagement.repository.LedgerLogRepository
import org.example.walletmanagement.repository.TransactionHistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class IdempotencyReplayServiceTest {

    @Mock private lateinit var transactionHistoryRepository: TransactionHistoryRepository
    @Mock private lateinit var ledgerLogRepository: LedgerLogRepository

    private val objectMapper = ObjectMapper()

    private fun buildService() =
        IdempotencyReplayService(transactionHistoryRepository, ledgerLogRepository, objectMapper)

    private val userId: UUID = UUID.randomUUID()

    private fun txn(operation: OperationType, status: TransactionStatus, amount: BigDecimal): TransactionHistoryEntity {
        val wallet = WalletEntity(userId = userId, balance = BigDecimal("999.00"))
        return TransactionHistoryEntity(
            wallet = wallet, idempotencyKey = KEY, operation = operation, status = status, amount = amount
        )
    }

    @Test
    fun `returns null when no transaction has the key`() {
        `when`(transactionHistoryRepository.findByIdempotencyKeyAndUserId(KEY, userId)).thenReturn(Optional.empty())
        assertNull(buildService().reconstruct(KEY, userId))
    }

    @Test
    fun `returns null when the transaction is not SUCCESS`() {
        `when`(transactionHistoryRepository.findByIdempotencyKeyAndUserId(KEY, userId))
            .thenReturn(Optional.of(txn(OperationType.DEPOSIT, TransactionStatus.STARTED, BigDecimal("40.00"))))
        assertNull(buildService().reconstruct(KEY, userId))
    }

    @Test
    fun `returns null when the ledger entry is missing`() {
        val t = txn(OperationType.DEPOSIT, TransactionStatus.SUCCESS, BigDecimal("40.00"))
        `when`(transactionHistoryRepository.findByIdempotencyKeyAndUserId(KEY, userId)).thenReturn(Optional.of(t))
        `when`(ledgerLogRepository.findByTransaction_Id(t.id)).thenReturn(Optional.empty())
        assertNull(buildService().reconstruct(KEY, userId))
    }

    @Test
    fun `rebuilds deposit response with balanceAfter from the credit ledger`() {
        val t = txn(OperationType.DEPOSIT, TransactionStatus.SUCCESS, BigDecimal("40.00"))
        val ledger = LedgerLogEntity(
            wallet = t.wallet, transaction = t, type = LedgerType.CREDIT,
            amount = BigDecimal("40.00"), balanceBefore = BigDecimal("100.00")
        )
        `when`(transactionHistoryRepository.findByIdempotencyKeyAndUserId(KEY, userId)).thenReturn(Optional.of(t))
        `when`(ledgerLogRepository.findByTransaction_Id(t.id)).thenReturn(Optional.of(ledger))

        val captured = buildService().reconstruct(KEY, userId)!!
        assertEquals(201, captured.status)
        val dto = objectMapper.readValue(captured.body, DepositResponse::class.java)
        assertEquals(t.id, dto.transactionId)
        assertEquals(BigDecimal("140.00"), dto.balanceAfter) // 100 + 40, not the live 999
        assertEquals(TransactionStatus.SUCCESS.name, dto.status)
    }

    @Test
    fun `rebuilds trade response with balanceAfter from the debit ledger`() {
        val t = txn(OperationType.TRADE, TransactionStatus.SUCCESS, BigDecimal("30.00"))
        val ledger = LedgerLogEntity(
            wallet = t.wallet, transaction = t, type = LedgerType.DEBIT,
            amount = BigDecimal("30.00"), balanceBefore = BigDecimal("100.00")
        )
        `when`(transactionHistoryRepository.findByIdempotencyKeyAndUserId(KEY, userId)).thenReturn(Optional.of(t))
        `when`(ledgerLogRepository.findByTransaction_Id(t.id)).thenReturn(Optional.of(ledger))

        val captured = buildService().reconstruct(KEY, userId)!!
        val dto = objectMapper.readValue(captured.body, TradeResponse::class.java)
        assertEquals(BigDecimal("70.00"), dto.balanceAfter) // 100 - 30
    }

    companion object {
        private const val KEY = "idem-key-1"
    }
}
