package org.example.walletmanagement.service

import org.example.walletmanagement.dto.DepositRequest
import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.dto.TradeRequest
import org.example.walletmanagement.dto.TradeResponse
import org.example.walletmanagement.entity.OperationType
import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.example.walletmanagement.entity.TransactionStatus
import org.example.walletmanagement.entity.WalletEntity
import org.example.walletmanagement.exception.InsufficientFundsException
import org.example.walletmanagement.exception.ResourceNotFoundException
import org.example.walletmanagement.mapper.WalletMapper
import org.example.walletmanagement.repository.TransactionHistoryRepository
import org.example.walletmanagement.repository.WalletRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WalletServiceTest {

    @Mock
    private lateinit var walletRepository: WalletRepository

    @Mock
    private lateinit var walletTransactionService: WalletTransactionService

    @Mock
    private lateinit var transactionHistoryRepository: TransactionHistoryRepository

    @InjectMocks
    private lateinit var walletMapper: WalletMapper

    private fun buildService() = WalletService(
        walletRepository,
        transactionHistoryRepository,
        walletTransactionService,
        walletMapper
    )

    private fun startedTxn(wallet: WalletEntity, amount: BigDecimal, operation: OperationType) =
        TransactionHistoryEntity(
            wallet = wallet,
            idempotencyKey = KEY,
            operation = operation,
            status = TransactionStatus.STARTED,
            amount = amount
        )

    // ─── getBalance ───────────────────────────────────────────────────────────

    @Test
    fun `getBalance returns wallet response when wallet exists`() {
        val userId = UUID.randomUUID()
        val wallet = WalletEntity(userId = userId, balance = BigDecimal("100.00"))
        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet))

        val response = buildService().getBalance(userId)

        assertEquals(wallet.id, response.walletId)
        assertEquals(userId, response.userId)
        assertEquals(BigDecimal("100.00"), response.balance)
    }

    @Test
    fun `getBalance throws ResourceNotFoundException when wallet does not exist`() {
        val userId = UUID.randomUUID()
        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.empty())

        assertThrows<ResourceNotFoundException> { buildService().getBalance(userId) }
    }

    // ─── deposit ──────────────────────────────────────────────────────────────

    @Test
    fun `deposit delegates to applyDeposit and returns its response`() {
        val userId = UUID.randomUUID()
        val amount = BigDecimal("50.00")
        val wallet = WalletEntity(userId = userId, balance = BigDecimal.ZERO)
        val txn = startedTxn(wallet, amount, OperationType.DEPOSIT)
        val expected = depositResponse(userId, wallet.id, amount)

        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet))
        `when`(transactionHistoryRepository.save(any(TransactionHistoryEntity::class.java))).thenReturn(txn)
        `when`(walletTransactionService.applyDeposit(userId, txn, amount)).thenReturn(expected)

        val response = buildService().deposit(userId, DepositRequest(amount), KEY)

        assertSame(expected, response)
        verify(walletTransactionService).applyDeposit(userId, txn, amount)
        verifyNoMoreInteractions(walletTransactionService)
    }

    @Test
    fun `deposit throws ResourceNotFoundException when wallet does not exist`() {
        val userId = UUID.randomUUID()
        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.empty())

        assertThrows<ResourceNotFoundException> {
            buildService().deposit(userId, DepositRequest(BigDecimal("10.00")), KEY)
        }
        verifyNoInteractions(walletTransactionService)
    }

    @Test
    fun `deposit marks transaction failed and rethrows when applyDeposit fails`() {
        val userId = UUID.randomUUID()
        val amount = BigDecimal("50.00")
        val wallet = WalletEntity(userId = userId, balance = BigDecimal.ZERO)
        val txn = startedTxn(wallet, amount, OperationType.DEPOSIT)

        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet))
        `when`(transactionHistoryRepository.save(any(TransactionHistoryEntity::class.java))).thenReturn(txn)
        `when`(walletTransactionService.applyDeposit(userId, txn, amount)).thenThrow(RuntimeException("boom"))

        assertThrows<RuntimeException> { buildService().deposit(userId, DepositRequest(amount), KEY) }
        verify(walletTransactionService).markFailed(txn, "boom")
    }

    // ─── trade ────────────────────────────────────────────────────────────────

    @Test
    fun `trade delegates to applyTrade and returns its response`() {
        val userId = UUID.randomUUID()
        val amount = BigDecimal("75.00")
        val wallet = WalletEntity(userId = userId, balance = BigDecimal("200.00"))
        val txn = startedTxn(wallet, amount, OperationType.TRADE)
        val expected = tradeResponse(userId, wallet.id, amount)

        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet))
        `when`(transactionHistoryRepository.save(any(TransactionHistoryEntity::class.java))).thenReturn(txn)
        `when`(walletTransactionService.applyTrade(userId, txn, amount)).thenReturn(expected)

        val response = buildService().trade(userId, TradeRequest(amount), KEY)

        assertSame(expected, response)
        verify(walletTransactionService).applyTrade(userId, txn, amount)
        verifyNoMoreInteractions(walletTransactionService)
    }

    @Test
    fun `trade throws ResourceNotFoundException when wallet does not exist`() {
        val userId = UUID.randomUUID()
        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.empty())

        assertThrows<ResourceNotFoundException> {
            buildService().trade(userId, TradeRequest(BigDecimal("10.00")), KEY)
        }
        verifyNoInteractions(walletTransactionService)
    }

    @Test
    fun `trade marks transaction failed and rethrows when applyTrade reports insufficient funds`() {
        val userId = UUID.randomUUID()
        val amount = BigDecimal("500.00")
        val wallet = WalletEntity(userId = userId, balance = BigDecimal("10.00"))
        val txn = startedTxn(wallet, amount, OperationType.TRADE)

        `when`(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet))
        `when`(transactionHistoryRepository.save(any(TransactionHistoryEntity::class.java))).thenReturn(txn)
        `when`(walletTransactionService.applyTrade(userId, txn, amount))
            .thenThrow(InsufficientFundsException("Insufficient funds"))

        assertThrows<InsufficientFundsException> { buildService().trade(userId, TradeRequest(amount), KEY) }
        verify(walletTransactionService).markFailed(txn, "Insufficient funds")
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private fun depositResponse(userId: UUID, walletId: UUID, amount: BigDecimal) =
        DepositResponse(UUID.randomUUID(), walletId, userId, amount, amount, TransactionStatus.SUCCESS.name, Instant.now())

    private fun tradeResponse(userId: UUID, walletId: UUID, amount: BigDecimal) =
        TradeResponse(UUID.randomUUID(), walletId, userId, amount, BigDecimal.ZERO, TransactionStatus.SUCCESS.name, Instant.now())

    companion object {
        private const val KEY = "idem-key-1"
    }
}
