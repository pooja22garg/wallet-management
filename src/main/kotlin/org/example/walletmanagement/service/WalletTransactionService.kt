package org.example.walletmanagement.service

import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.dto.TradeResponse
import org.example.walletmanagement.exception.InsufficientFundsException
import org.example.walletmanagement.exception.ResourceNotFoundException
import org.example.walletmanagement.entity.LedgerLogEntity
import org.example.walletmanagement.entity.LedgerType
import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.example.walletmanagement.entity.TransactionStatus
import org.example.walletmanagement.mapper.WalletMapper
import org.example.walletmanagement.repository.LedgerLogRepository
import org.example.walletmanagement.repository.TransactionHistoryRepository
import org.example.walletmanagement.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class WalletTransactionService(
    private val walletRepository: WalletRepository,
    private val ledgerLogRepository: LedgerLogRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val walletMapper: WalletMapper
) {

    /**
     * Atomically credits the wallet, writes the credit ledger entry and marks the
     * transaction SUCCESS. Idempotency persistence is handled by the HTTP filter.
     */
    @Transactional
    fun applyDeposit(userId: UUID, txn: TransactionHistoryEntity, amount: BigDecimal): DepositResponse {
        val wallet = walletRepository.findByUserIdForUpdate(userId)
            .orElseThrow { ResourceNotFoundException("Wallet not found for userId: $userId") }

        val balanceBefore = wallet.balance

        ledgerLogRepository.save(
            LedgerLogEntity(
                wallet = wallet,
                transaction = txn,
                type = LedgerType.CREDIT,
                amount = amount,
                balanceBefore = balanceBefore
            )
        )

        wallet.balance = balanceBefore.add(amount)
        wallet.updatedAt = Instant.now()
        walletRepository.save(wallet)

        txn.status = TransactionStatus.SUCCESS
        transactionHistoryRepository.save(txn)

        return walletMapper.toDepositResponse(txn, wallet)
    }

    /**
     * Atomically validates funds, debits the wallet, writes the debit ledger entry and
     * marks the transaction SUCCESS. Idempotency persistence is handled by the HTTP filter.
     */
    @Transactional
    fun applyTrade(userId: UUID, txn: TransactionHistoryEntity, amount: BigDecimal): TradeResponse {
        val wallet = walletRepository.findByUserIdForUpdate(userId)
            .orElseThrow { ResourceNotFoundException("Wallet not found for userId: $userId") }

        if (wallet.balance < amount) {
            throw InsufficientFundsException(
                "Insufficient funds: balance=${wallet.balance}, requested=$amount"
            )
        }

        val balanceBefore = wallet.balance
        wallet.balance = balanceBefore.subtract(amount)
        wallet.updatedAt = Instant.now()
        walletRepository.save(wallet)

        ledgerLogRepository.save(
            LedgerLogEntity(
                wallet = wallet,
                transaction = txn,
                type = LedgerType.DEBIT,
                amount = amount,
                balanceBefore = balanceBefore
            )
        )

        txn.status = TransactionStatus.SUCCESS
        transactionHistoryRepository.save(txn)

        return walletMapper.toTradeResponse(txn, wallet)
    }

    /**
     * Marks the transaction FAILED in its own transaction (survives the rolled-back
     * business transaction) and releases the idempotency key so the client can retry —
     * the key is unique on the txn row and only a SUCCESS txn needs to retain it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(txn: TransactionHistoryEntity, reason: String?) {
        txn.status = TransactionStatus.FAILED
        txn.failureReason = reason
        txn.idempotencyKey = null
        transactionHistoryRepository.save(txn)
    }
}
