package org.example.walletmanagement.service

import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.dto.TradeResponse
import org.example.walletmanagement.entity.LedgerType
import org.example.walletmanagement.entity.OperationType
import org.example.walletmanagement.entity.TransactionStatus
import org.example.walletmanagement.repository.LedgerLogRepository
import org.example.walletmanagement.repository.TransactionHistoryRepository
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** A response captured for idempotent replay. */
data class CapturedResponse(val status: Int, val contentType: String?, val body: String)

/**
 * Rebuilds the HTTP response for a request whose effect committed but whose response was
 * never stored (e.g. a crash between commit and [IdempotencyService.storeResponse]).
 *
 * It is the fallback for a stuck IN_PROGRESS record: the committed transaction is the
 * atomic reference (looked up by idempotency key), and the response is recomputed from it.
 * Scoped to SUCCESS — failures moved no money and don't need rescuing.
 */
@Service
class IdempotencyReplayService(
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val ledgerLogRepository: LedgerLogRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional(readOnly = true)
    fun reconstruct(key: String, userId: UUID): CapturedResponse? {
        val txn = transactionHistoryRepository.findByIdempotencyKeyAndUserId(key, userId).orElse(null) ?: return null
        if (txn.status != TransactionStatus.SUCCESS) return null

        // balanceAfter is derived from the immutable ledger entry, not the live wallet,
        // whose balance has since moved on.
        val ledger = ledgerLogRepository.findByTransaction_Id(txn.id).orElse(null) ?: return null
        val balanceAfter = when (ledger.type) {
            LedgerType.CREDIT -> ledger.balanceBefore.add(txn.amount)
            LedgerType.DEBIT -> ledger.balanceBefore.subtract(txn.amount)
        }

        val wallet = txn.wallet
        val body = when (txn.operation) {
            OperationType.DEPOSIT -> objectMapper.writeValueAsString(
                DepositResponse(txn.id, wallet.id, wallet.userId, txn.amount, balanceAfter, txn.status.name, txn.createdAt)
            )
            OperationType.TRADE -> objectMapper.writeValueAsString(
                TradeResponse(txn.id, wallet.id, wallet.userId, txn.amount, balanceAfter, txn.status.name, txn.createdAt)
            )
        }
        return CapturedResponse(201, MediaType.APPLICATION_JSON_VALUE, body)
    }
}
