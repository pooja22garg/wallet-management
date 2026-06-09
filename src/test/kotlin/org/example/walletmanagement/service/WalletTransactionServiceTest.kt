package org.example.walletmanagement.service

import org.example.walletmanagement.entity.LedgerLogEntity
import org.example.walletmanagement.entity.LedgerType
import org.example.walletmanagement.entity.OperationType
import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.example.walletmanagement.entity.TransactionStatus
import org.example.walletmanagement.entity.WalletEntity
import org.example.walletmanagement.exception.InsufficientFundsException
import org.example.walletmanagement.exception.ResourceNotFoundException
import org.example.walletmanagement.mapper.WalletMapper
import org.example.walletmanagement.repository.LedgerLogRepository
import org.example.walletmanagement.repository.TransactionHistoryRepository
import org.example.walletmanagement.repository.WalletRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WalletTransactionServiceTest {

    @Mock
    private lateinit var walletRepository: WalletRepository

    @Mock
    private lateinit var ledgerLogRepository: LedgerLogRepository

    @Mock
    private lateinit var transactionHistoryRepository: TransactionHistoryRepository

    @InjectMocks
    private lateinit var walletMapper: WalletMapper

    private fun buildService() = WalletTransactionService(
        walletRepository,
        ledgerLogRepository,
        transactionHistoryRepository,
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

    private fun captureSavedLedger(): () -> LedgerLogEntity? {
        var saved: LedgerLogEntity? = null
        `when`(ledgerLogRepository.save(any(LedgerLogEntity::class.java))).thenAnswer {
            saved = it.arguments[0] as LedgerLogEntity
            saved
        }
        return { saved }
    }

    // ─── applyDeposit ───────────────────────────────────────────────────────────

    @Test
    fun `applyDeposit credits balance, writes CREDIT ledger and marks SUCCESS`() {
        val userId = UUID.randomUUID()
        val amount = BigDecimal("50.00")
        val wallet = WalletEntity(userId = userId, balance = BigDecimal("100.00"))
        val txn = startedTxn(wallet, amount, OperationType.DEPOSIT)

        `when`(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet))
        `when`(walletRepository.save(any(WalletEntity::class.java))).thenReturn(wallet)
        val savedLedger = captureSavedLedger()

        val response = buildService().applyDeposit(userId, txn, amount)

        assertEquals(BigDecimal("150.00"), wallet.balance)
        assertEquals(TransactionStatus.SUCCESS, txn.status)

        assertEquals(LedgerType.CREDIT, savedLedger()!!.type)
        assertEquals(amount, savedLedger()!!.amount)
        assertEquals(BigDecimal("100.00"), savedLedger()!!.balanceBefore)

        assertEquals(amount, response.amount)
        assertEquals(BigDecimal("150.00"), response.balanceAfter)
        assertEquals(TransactionStatus.SUCCESS.name, response.status)

        verify(walletRepository).save(wallet)
        verify(transactionHistoryRepository).save(txn)
    }

    @Test
    fun `applyDeposit throws ResourceNotFoundException when wallet does not exist`() {
        val userId = UUID.randomUUID()
        val txn = startedTxn(WalletEntity(userId = userId), BigDecimal("50.00"), OperationType.DEPOSIT)

        `when`(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty())

        assertThrows<ResourceNotFoundException> {
            buildService().applyDeposit(userId, txn, BigDecimal("50.00"))
        }
        verifyNoInteractions(ledgerLogRepository, transactionHistoryRepository)
    }

    // ─── applyTrade ─────────────────────────────────────────────────────────────

    @Test
    fun `applyTrade debits balance, writes DEBIT ledger and marks SUCCESS`() {
        val userId = UUID.randomUUID()
        val amount = BigDecimal("75.00")
        val wallet = WalletEntity(userId = userId, balance = BigDecimal("200.00"))
        val txn = startedTxn(wallet, amount, OperationType.TRADE)

        `when`(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet))
        `when`(walletRepository.save(any(WalletEntity::class.java))).thenReturn(wallet)
        val savedLedger = captureSavedLedger()

        val response = buildService().applyTrade(userId, txn, amount)

        assertEquals(BigDecimal("125.00"), wallet.balance)
        assertEquals(TransactionStatus.SUCCESS, txn.status)

        assertEquals(LedgerType.DEBIT, savedLedger()!!.type)
        assertEquals(BigDecimal("200.00"), savedLedger()!!.balanceBefore)

        assertEquals(BigDecimal("125.00"), response.balanceAfter)
        assertEquals(TransactionStatus.SUCCESS.name, response.status)
    }

    @Test
    fun `applyTrade succeeds with exact balance leaving zero`() {
        val userId = UUID.randomUUID()
        val amount = BigDecimal("100.00")
        val wallet = WalletEntity(userId = userId, balance = amount)
        val txn = startedTxn(wallet, amount, OperationType.TRADE)

        `when`(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet))
        `when`(walletRepository.save(any(WalletEntity::class.java))).thenReturn(wallet)
        captureSavedLedger()

        buildService().applyTrade(userId, txn, amount)

        assertEquals(BigDecimal.ZERO.setScale(2), wallet.balance)
    }

    @Test
    fun `applyTrade throws InsufficientFundsException and does not mutate state when balance too low`() {
        val userId = UUID.randomUUID()
        val wallet = WalletEntity(userId = userId, balance = BigDecimal("10.00"))
        val txn = startedTxn(wallet, BigDecimal("50.00"), OperationType.TRADE)

        `when`(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet))

        assertThrows<InsufficientFundsException> {
            buildService().applyTrade(userId, txn, BigDecimal("50.00"))
        }

        assertEquals(BigDecimal("10.00"), wallet.balance)
        assertEquals(TransactionStatus.STARTED, txn.status)
        verifyNoInteractions(ledgerLogRepository, transactionHistoryRepository)
    }

    @Test
    fun `applyTrade throws ResourceNotFoundException when wallet does not exist`() {
        val userId = UUID.randomUUID()
        val txn = startedTxn(WalletEntity(userId = userId), BigDecimal("10.00"), OperationType.TRADE)

        `when`(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.empty())

        assertThrows<ResourceNotFoundException> {
            buildService().applyTrade(userId, txn, BigDecimal("10.00"))
        }
        verifyNoInteractions(ledgerLogRepository, transactionHistoryRepository)
    }

    // ─── markFailed ─────────────────────────────────────────────────────────────

    @Test
    fun `markFailed sets status FAILED with reason and releases the idempotency key`() {
        val wallet = WalletEntity(userId = UUID.randomUUID())
        val txn = startedTxn(wallet, BigDecimal("50.00"), OperationType.DEPOSIT)

        buildService().markFailed(txn, "boom")

        assertEquals(TransactionStatus.FAILED, txn.status)
        assertEquals("boom", txn.failureReason)
        assertNull(txn.idempotencyKey)
        verify(transactionHistoryRepository).save(txn)
    }

    companion object {
        private const val KEY = "idem-key-1"
    }
}
