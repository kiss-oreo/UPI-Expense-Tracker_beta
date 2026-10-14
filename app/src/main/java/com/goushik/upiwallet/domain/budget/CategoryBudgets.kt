package com.goushik.upiwallet.domain.budget

import com.goushik.upiwallet.data.CategoryBudgetEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.domain.insights.InsightsPeriod
import com.goushik.upiwallet.domain.insights.isSpend
import com.goushik.upiwallet.domain.insights.spendWindow
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Monthly category caps use the SAME expense predicate as Home, Insights and the global budget.
 * Rollover is positive unused allowance only; overspending never creates negative debt in next month.
 * It starts in the month the cap was set (changing a cap starts a new schedule). Envelopes are virtual
 * allocations, never actual transfers out of a bank account. All arithmetic is integer paise. */
data class CategoryBudgetStatus(
    val category: String,
    val basePaise: Long,
    val carriedPaise: Long,
    val spentPaise: Long,
    val monthStart: Long,
    val envelope: Boolean,
) {
    val effectiveLimitPaise: Long get() = basePaise + carriedPaise
    val meter: BudgetStatus get() = BudgetStatus(InsightsPeriod.MONTH, effectiveLimitPaise, spentPaise, monthStart)
}

fun categoryBudgetStatus(
    budget: CategoryBudgetEntity,
    txns: List<TransactionEntity>,
    ownVpas: Set<String>,
    ownNames: Set<String>,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): CategoryBudgetStatus {
    val current = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
    val monthStart = spendWindow(InsightsPeriod.MONTH, nowMs, zone = zone).startMs
    val byMonth = txns.asSequence()
        .filter { isSpend(it, ownVpas, ownNames) && (it.category ?: "Other") == budget.category }
        .filter { it.timestampEvent <= nowMs }
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestampEvent).atZone(zone)) }
        .mapValues { (_, entries) -> entries.sumOf { it.amountPaise } }
    val start = YearMonth.from(Instant.ofEpochMilli(budget.updatedAt).atZone(zone))
    var carried = 0L
    if (budget.rollover && !start.isAfter(current)) {
        var month = start
        while (month.isBefore(current)) {
            val available = budget.limitPaise + carried
            carried = (available - (byMonth[month] ?: 0L)).coerceAtLeast(0L)
            month = month.plusMonths(1)
        }
    }
    return CategoryBudgetStatus(
        budget.category, budget.limitPaise, carried, byMonth[current] ?: 0L, monthStart, budget.envelope,
    )
}
