package org.example.walletmanagement.repository

import org.example.walletmanagement.entity.LedgerLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface LedgerLogRepository : JpaRepository<LedgerLogEntity, UUID> {

    fun findByTransaction_Id(transactionId: UUID): Optional<LedgerLogEntity>
}
