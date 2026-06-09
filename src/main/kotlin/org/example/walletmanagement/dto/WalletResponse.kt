package org.example.walletmanagement.dto

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class WalletResponse(
    val walletId: UUID,
    val userId: UUID,
    val balance: BigDecimal
)
