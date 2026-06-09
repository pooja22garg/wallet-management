package org.example.walletmanagement.mapper

import org.example.walletmanagement.dto.DepositResponse
import org.example.walletmanagement.dto.TradeResponse
import org.example.walletmanagement.dto.WalletResponse
import org.example.walletmanagement.entity.TransactionHistoryEntity
import org.example.walletmanagement.entity.WalletEntity
import org.springframework.stereotype.Component

@Component
class WalletMapper {

    fun toWalletResponse(wallet: WalletEntity): WalletResponse = WalletResponse(
        walletId = wallet.id,
        userId = wallet.userId,
        balance = wallet.balance
    )

    fun toDepositResponse(txn: TransactionHistoryEntity, wallet: WalletEntity): DepositResponse = DepositResponse(
        transactionId = txn.id,
        walletId = wallet.id,
        userId = wallet.userId,
        amount = txn.amount,
        balanceAfter = wallet.balance,
        status = txn.status.name,
        createdAt = txn.createdAt
    )

    fun toTradeResponse(txn: TransactionHistoryEntity, wallet: WalletEntity): TradeResponse = TradeResponse(
        transactionId = txn.id,
        walletId = wallet.id,
        userId = wallet.userId,
        amount = txn.amount,
        balanceAfter = wallet.balance,
        status = txn.status.name,
        createdAt = txn.createdAt
    )
}
