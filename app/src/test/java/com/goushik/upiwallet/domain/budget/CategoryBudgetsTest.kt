package com.goushik.upiwallet.domain.budget

import com.goushik.upiwallet.data.CategoryBudgetEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnKind
import com.goushik.upiwallet.data.TxnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CategoryBudgetsTest {
    private val utc = ZoneOffset.UTC
    private fun at(m: Int, day: Int): Long = LocalDate.of(2026, m, day).atStartOfDay(utc).toInstant().toEpochMilli()
    private fun payment(id: String, amount: Long, whenAt: Long, category: String = "Food",
                        kind: TxnKind = TxnKind.EXPENSE, status: TxnStatus = TxnStatus.CONFIRMED) =
        TransactionEntity(id = id, amountPaise = amount, direction = Direction.DEBIT, kind = kind,
            status = status, category = category, timestampEvent = whenAt, timestampCaptured = whenAt,
            source = Source.MANUAL)

    @Test fun `unused allowance rolls across month boundary in integer paise`() {
        val budget = CategoryBudgetEntity("Food", 10_000, rollover = true, envelope = true, updatedAt = at(1, 1))
        val rows = listOf(payment("jan", 6_000, at(1, 5)), payment("feb", 11_200, at(2, 4)))
        val status = categoryBudgetStatus(budget, rows, emptySet(), emptySet(), at(2, 15), utc)
        assertEquals(4_000L, status.carriedPaise)
        assertEquals(14_000L, status.effectiveLimitPaise)
        assertEquals(11_200L, status.spentPaise)
        assertEquals(80, status.meter.pct)
        assertEquals(80, status.meter.reachedThreshold())
        assertEquals(2_800L, status.meter.remainingPaise)
        assertTrue(status.envelope)
    }

    @Test fun `overspending never creates a negative rollover debt`() {
        val budget = CategoryBudgetEntity("Shopping", 10_000, rollover = true, envelope = false, updatedAt = at(1, 1))
        val rows = listOf(payment("over", 13_000, at(1, 5), category = "Shopping"),
            payment("feb", 10_000, at(2, 4), category = "Shopping"))
        val status = categoryBudgetStatus(budget, rows, emptySet(), emptySet(), at(2, 15), utc)
        assertEquals(0L, status.carriedPaise)
        assertTrue(status.meter.isOver)
        assertEquals(100, status.meter.reachedThreshold())
    }

    @Test fun `only this category's real spending counts, not transfers refunds or failed payments`() {
        val budget = CategoryBudgetEntity("Food", 20_000, rollover = false, envelope = false, updatedAt = at(2, 1))
        val rows = listOf(payment("spent", 5_000, at(2, 5)),
            payment("transfer", 12_000, at(2, 6), kind = TxnKind.TRANSFER),
            payment("discarded", 9_000, at(2, 7), status = TxnStatus.DISCARDED),
            payment("other", 7_000, at(2, 8), category = "Shopping"))
        val status = categoryBudgetStatus(budget, rows, emptySet(), emptySet(), at(2, 15), utc)
        assertEquals(5_000L, status.spentPaise)
        assertEquals(0L, status.carriedPaise)
        assertFalse(status.meter.isNear)
    }
}
