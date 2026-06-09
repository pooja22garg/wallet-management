package org.example.walletmanagement.repository

import org.example.walletmanagement.entity.IdempotencyEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface IdempotencyRepository : JpaRepository<IdempotencyEntity, UUID> {

    fun findByIdempotencyKeyAndUserId(idempotencyKey: String, userId: UUID): Optional<IdempotencyEntity>
}
