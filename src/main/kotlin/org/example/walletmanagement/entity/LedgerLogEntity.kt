package org.example.walletmanagement.entity

import jakarta.persistence.*
import org.hibernate.annotations.Check
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "ledger_logs",
    indexes = [Index(name = "idx_ledger_wallet_created", columnList = "wallet_id, created_at")]
)
@Check(name = "chk_ledger_amount_positive", constraints = "amount > 0")
class LedgerLogEntity(
    @Id
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "UUID")
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    val wallet: WalletEntity,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "txn_id", nullable = false, updatable = false)
    val transaction: TransactionHistoryEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    val type: LedgerType,

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    val amount: BigDecimal,

    @Column(name = "balance_before", nullable = false, precision = 10, scale = 2)
    val balanceBefore: BigDecimal,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
