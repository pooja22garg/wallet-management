package org.example.walletmanagement.dto

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class DepositResponse(
    val transactionId: UUID,
    val walletId: UUID,
    val userId: UUID,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val status: String,
    val createdAt: Instant
)
