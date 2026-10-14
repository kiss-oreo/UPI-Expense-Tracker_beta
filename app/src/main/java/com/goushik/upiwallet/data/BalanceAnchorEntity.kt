package com.goushik.upiwallet.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A real money container (bank, cash, card or wallet) with a user-verified opening balance.
 * The table name stays `balance_anchors` for a non-destructive upgrade from 1.x. Each transaction
 * references this stable [id]; re-anchoring must update the row, NOT replace its ID. [openedAt]
 * prevents historic payments being applied to an account added after those payments occurred.
 * Linked UPI IDs live in payment_instruments and never add another baseline to this table. */
@Entity(tableName = "balance_anchors")
data class BalanceAnchorEntity(
    @PrimaryKey val id: String,
    val accountLabel: String,
    val baselinePaise: Long,
    val anchoredAt: Long,
    @ColumnInfo(defaultValue = "'BANK'") val accountType: AccountType = AccountType.BANK,
    val accountLast4: String? = null,
    @ColumnInfo(defaultValue = "0") val openedAt: Long = 0L,
)
