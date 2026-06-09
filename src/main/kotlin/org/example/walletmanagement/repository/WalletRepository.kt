package org.example.walletmanagement.repository

import jakarta.persistence.LockModeType
import org.example.walletmanagement.entity.WalletEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface WalletRepository : JpaRepository<WalletEntity, UUID> {

    fun findByUserId(userId: UUID): Optional<WalletEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WalletEntity w WHERE w.userId = :userId")
    fun findByUserIdForUpdate(userId: UUID): Optional<WalletEntity>
}
