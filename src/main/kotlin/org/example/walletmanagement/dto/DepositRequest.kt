package org.example.walletmanagement.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

data class DepositRequest(
    @field:NotNull(message = "amount is required")
    @field:DecimalMin(value = "0.01", message = "amount must be greater than 0")
    @field:Digits(integer = 10, fraction = 2, message = "amount must have at most 10 integer digits and 2 decimal places")
    val amount: BigDecimal?

)
