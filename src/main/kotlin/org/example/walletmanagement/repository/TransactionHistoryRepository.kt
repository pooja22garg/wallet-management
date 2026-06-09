package org.example.walletmanagement.repository

import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface TransactionHistoryRepository : JpaRepository<TransactionHistoryEntity, UUID> {

    fun findByIdempotencyKeyAndUserId(idempotencyKey: String, userId: UUID): Optional<TransactionHistoryEntity>
}
