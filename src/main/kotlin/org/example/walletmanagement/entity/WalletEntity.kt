package org.example.walletmanagement.entity

import jakarta.persistence.*
import org.hibernate.annotations.Check
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "wallets",
    uniqueConstraints = [UniqueConstraint(name = "uq_wallets_user_id", columnNames = ["user_id"])]
)
@Check(name = "chk_wallets_balance_non_negative", constraints = "balance >= 0")
class WalletEntity(
    @Id
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "UUID")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, unique = true, columnDefinition = "UUID")
    val userId: UUID,

    @Column(name = "balance", nullable = false, precision = 10, scale = 2)
    var balance: BigDecimal = BigDecimal.ZERO,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
//    @OneToMany(mappedBy = "wallet", fetch = FetchType.LAZY)
//    val ledgerLogs: MutableList<LedgerLogEntity> = mutableListOf()
//
//    @OneToMany(mappedBy = "wallet", fetch = FetchType.LAZY)
//    val transactions: MutableList<TransactionHistoryEntity> = mutableListOf()

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
