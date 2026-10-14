package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An identifier used to pay, NOT an extra balance. Many UPI handles can point to one bank account. */
@Entity(tableName = "payment_instruments", indices = [Index("accountId")])
data class PaymentInstrumentEntity(
    @PrimaryKey val id: String,
    val label: String,
    val vpa: String,
    val accountId: String,
)
