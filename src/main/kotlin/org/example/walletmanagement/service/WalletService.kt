package org.example.walletmanagement.service

import org.example.walletmanagement.dto.DepositRequest
import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.dto.TradeRequest
import org.example.walletmanagement.dto.TradeResponse
import org.example.walletmanagement.dto.WalletResponse
import org.example.walletmanagement.entity.OperationType
import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.example.walletmanagement.entity.TransactionStatus
import org.example.walletmanagement.entity.WalletEntity
import org.example.walletmanagement.exception.ResourceNotFoundException
import org.example.walletmanagement.exception.WalletAlreadyExistsException
import org.example.walletmanagement.mapper.WalletMapper
import org.example.walletmanagement.repository.TransactionHistoryRepository
import org.example.walletmanagement.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class WalletService(
    private val walletRepository: WalletRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val walletTransactionService: WalletTransactionService,
    private val walletMapper: WalletMapper
) {

    @Transactional
    fun createWallet(userId: UUID): WalletResponse {
        if (walletRepository.findByUserId(userId).isPresent) {
            throw WalletAlreadyExistsException("Wallet already exists for userId: $userId")
        }
        val wallet = walletRepository.save(WalletEntity(userId = userId))
        return walletMapper.toWalletResponse(wallet)
    }

    fun getBalance(userId: UUID): WalletResponse {
        val wallet = walletRepository.findByUserId(userId)
            .orElseThrow { ResourceNotFoundException("Wallet not found for userId: $userId") }
        return walletMapper.toWalletResponse(wallet)
    }

    fun deposit(userId: UUID, request: DepositRequest, idempotencyKey: String): DepositResponse {
        val amount = request.amount!!

        val wallet = walletRepository.findByUserId(userId)
            .orElseThrow { ResourceNotFoundException("Wallet not found for userId: $userId") }

        val txn = createTransactionHistory(wallet, amount, idempotencyKey, OperationType.DEPOSIT)
        return try {
            walletTransactionService.applyDeposit(userId, txn, amount)
        } catch (e: Exception) {
            walletTransactionService.markFailed(txn, e.message)
            println("Deposit failed for userId: $userId, amount: $amount, error: ${e.message}")
            throw e
        }
    }

    fun trade(userId: UUID, request: TradeRequest, idempotencyKey: String): TradeResponse {
        val amount = request.amount!!

        val wallet = walletRepository.findByUserId(userId)
            .orElseThrow { ResourceNotFoundException("Wallet not found for userId: $userId") }

        val txn = createTransactionHistory(wallet, amount, idempotencyKey, OperationType.TRADE)
        return try {
            walletTransactionService.applyTrade(userId, txn, amount)
        } catch (e: Exception) {
            walletTransactionService.markFailed(txn, e.message)
            println("Trade failed for userId: $userId, amount: $amount, error: ${e.message}")
            throw e
        }
    }

    fun createTransactionHistory(wallet: WalletEntity, amount: BigDecimal, idempotencyKey: String?, operation: OperationType): TransactionHistoryEntity {
        return transactionHistoryRepository.save(
            TransactionHistoryEntity(
                wallet = wallet,
                idempotencyKey = idempotencyKey,
                operation = operation,
                status = TransactionStatus.STARTED,
                amount = amount
            )
        )
    }
}
