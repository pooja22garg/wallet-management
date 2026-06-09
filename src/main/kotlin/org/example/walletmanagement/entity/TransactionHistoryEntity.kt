package org.example.walletmanagement.entity

import jakarta.persistence.*
import org.hibernate.annotations.Check
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "transaction_history",
    uniqueConstraints = [UniqueConstraint(name = "uq_txn_key_user", columnNames = ["idempotency_key", "user_id"])]
)
@Check(name = "chk_txn_amount_positive", constraints = "amount > 0")
class TransactionHistoryEntity(
    @Id
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "UUID")
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    val wallet: WalletEntity,

    // Denormalized from the wallet so idempotency keys are unique *per user* (the key
    // may legitimately repeat across users).
    @Column(name = "user_id", nullable = false, updatable = false, columnDefinition = "UUID")
    val userId: UUID = wallet.userId,

    @Column(name = "idempotency_key", nullable = true)
    var idempotencyKey: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 10)
    val operation: OperationType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    var status: TransactionStatus = TransactionStatus.STARTED,

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    val amount: BigDecimal,

    @Column(name = "failure_reason", nullable = true, columnDefinition = "TEXT")
    var failureReason: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
//) {
//    @OneToMany(mappedBy = "transaction", fetch = FetchType.LAZY)
//    val ledgerLogs: MutableList<LedgerLogEntity> = mutableListOf()
//}
