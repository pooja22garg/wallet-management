package org.example.walletmanagement.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "idempotency",
    indexes = [Index(name = "idx_idempotency_user_id", columnList = "user_id, idempotency_key")],
    uniqueConstraints = [UniqueConstraint(name = "uq_idem_key_user", columnNames = ["idempotency_key", "user_id"])]
)
class IdempotencyEntity(
    @Id
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "UUID")
    val id: UUID = UUID.randomUUID(),

    // Unique per user (see uq_idem_key_user) — the same key may repeat across users.
    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    val userId: UUID,

    @Column(name = "endpoint", nullable = false)
    val endpoint: String,

    @Column(name = "request_hash", nullable = false, length = 64)
    val requestHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    var status: IdempotencyStatus = IdempotencyStatus.IN_PROGRESS,

    @Column(name = "response_body", nullable = true, columnDefinition = "TEXT")
    var responseBody: String? = null,

    @Column(name = "response_status", nullable = true)
    var responseStatus: Int? = null,

    @Column(name = "response_content_type", nullable = true)
    var responseContentType: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant = Instant.now().plusSeconds(86400L)
)
