package org.example.walletmanagement.service

import org.example.walletmanagement.entity.IdempotencyEntity
import org.example.walletmanagement.entity.IdempotencyStatus
import org.example.walletmanagement.repository.IdempotencyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class IdempotencyService(
    private val idempotencyRepository: IdempotencyRepository
) {

    @Transactional(readOnly = true)
    fun find(key: String, userId: UUID): IdempotencyEntity? =
        idempotencyRepository.findByIdempotencyKeyAndUserId(key, userId).orElse(null)

    /**
     * Persists a new IN_PROGRESS record in its own committed transaction so the dedup
     * marker exists before the business request is processed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun initiate(key: String, userId: UUID, endpoint: String, requestHash: String): IdempotencyEntity =
        idempotencyRepository.save(
            IdempotencyEntity(
                idempotencyKey = key,
                userId = userId,
                endpoint = endpoint,
                requestHash = requestHash,
                status = IdempotencyStatus.IN_PROGRESS
            )
        )

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun delete(idempotencyEntity: IdempotencyEntity) {
        idempotencyRepository.delete(idempotencyEntity)
    }

    /**
     * Resets an existing record back to IN_PROGRESS (clearing any stored response) so a
     * retried request reuses the same row instead of inserting a new one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markInProgress(key: String, userId: UUID) {
        idempotencyRepository.findByIdempotencyKeyAndUserId(key, userId).ifPresent { record ->
            record.status = IdempotencyStatus.IN_PROGRESS
            record.responseStatus = null
            record.responseContentType = null
            record.responseBody = null
            idempotencyRepository.save(record)
        }
    }

    /**
     * Stores the captured HTTP response, so future requests
     * with the same key are replayed verbatim.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun storeResponse(key: String, userId: UUID, status: Int, contentType: String?, body: String, idempotencyStatus: IdempotencyStatus) {
        idempotencyRepository.findByIdempotencyKeyAndUserId(key, userId).ifPresent { record ->
            record.status = idempotencyStatus
            record.responseStatus = status
            record.responseContentType = contentType
            record.responseBody = body
            idempotencyRepository.save(record)
        }
    }
}
