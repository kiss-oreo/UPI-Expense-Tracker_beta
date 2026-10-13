package com.goushik.upiwallet.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One monthly spending limit per category. `rollover` carries *unused* money from previous months;
 * `envelope` marks money virtually earmarked for this category (it never changes a bank balance).
 * updatedAt starts a new rollover schedule when a limit is changed. Alert stamps dedup per month. */
@Entity(tableName = "category_budgets")
data class CategoryBudgetEntity(
    @PrimaryKey val category: String,
    val limitPaise: Long,
    val rollover: Boolean,
    val envelope: Boolean,
    val updatedAt: Long,
    val lastAlertedThreshold: Int = 0,
    val lastAlertedPeriodStart: Long = 0L,
)
