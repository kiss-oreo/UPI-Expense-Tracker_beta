package com.goushik.upiwallet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryBudgetDao {
    @Query("SELECT * FROM category_budgets ORDER BY category")
    fun observeAll(): Flow<List<CategoryBudgetEntity>>

    @Query("SELECT * FROM category_budgets")
    suspend fun all(): List<CategoryBudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: CategoryBudgetEntity)

    @Query("DELETE FROM category_budgets WHERE category = :category")
    suspend fun delete(category: String)

    @Query("UPDATE category_budgets SET lastAlertedThreshold = :threshold, lastAlertedPeriodStart = :periodStart " +
        "WHERE category = :category AND (lastAlertedPeriodStart != :periodStart OR lastAlertedThreshold < :threshold)")
    suspend fun markAlerted(category: String, threshold: Int, periodStart: Long): Int
}
