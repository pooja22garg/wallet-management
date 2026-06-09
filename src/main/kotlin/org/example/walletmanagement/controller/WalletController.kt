package org.example.walletmanagement.controller

import jakarta.validation.Valid
import org.example.walletmanagement.dto.DepositRequest
import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.dto.TradeRequest
import org.example.walletmanagement.dto.TradeResponse
import org.example.walletmanagement.dto.WalletResponse
import org.example.walletmanagement.service.WalletService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/wallets")
class WalletController(
    private val walletService: WalletService
) {

    @PostMapping("/{userId}")
    fun createWallet(@PathVariable userId: UUID): ResponseEntity<WalletResponse> {
        return ResponseEntity.status(HttpStatus.CREATED).body(walletService.createWallet(userId))
    }

    @GetMapping("/{userId}")
    fun getBalance(@PathVariable userId: UUID): ResponseEntity<WalletResponse> {
        return ResponseEntity.ok(walletService.getBalance(userId));
    }


    @PostMapping("/{userId}/deposit")
    fun deposit(
        @PathVariable userId: UUID,
        @RequestBody @Valid request: DepositRequest,
        @RequestHeader(value = "Idempotency-Key", required = true) idempotencyKey: String
    ): ResponseEntity<DepositResponse> {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(walletService.deposit(userId, request, idempotencyKey));
    }


    @PostMapping("/{userId}/trade")
    fun trade(
        @PathVariable userId: UUID,
        @RequestBody @Valid request: TradeRequest,
        @RequestHeader(value = "Idempotency-Key", required = true) idempotencyKey: String
    ): ResponseEntity<TradeResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(walletService.trade(userId, request, idempotencyKey))
}
