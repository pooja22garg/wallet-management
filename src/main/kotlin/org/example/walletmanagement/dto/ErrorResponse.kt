package org.example.walletmanagement.dto

data class ErrorResponse(
    val status: Int,
    val message: String,
    val path: String
)
