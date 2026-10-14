package com.goushik.upiwallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentInstrumentDao {
    @Query("SELECT * FROM payment_instruments ORDER BY label")
    fun observeAll(): Flow<List<PaymentInstrumentEntity>>

    @Query("SELECT * FROM payment_instruments")
    suspend fun all(): List<PaymentInstrumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(instrument: PaymentInstrumentEntity)

    @Query("DELETE FROM payment_instruments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM payment_instruments WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}
