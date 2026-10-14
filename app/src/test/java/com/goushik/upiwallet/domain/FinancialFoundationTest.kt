package com.goushik.upiwallet.domain

import com.goushik.upiwallet.data.AccountType
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.PaymentInstrumentEntity
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnKind
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.domain.insights.isSpend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialFoundationTest {
    private val bank = BalanceAnchorEntity("bank", "HDFC", 100_000, 1_000,
        AccountType.BANK, accountLast4 = "1234")
    private val cash = BalanceAnchorEntity("cash", "Cash", 20_000, 1_000,
        AccountType.CASH)

    private fun row(id: String, amount: Long, dir: Direction, kind: TxnKind?,
                    from: String? = null, to: String? = null, status: TxnStatus = TxnStatus.CONFIRMED,
                    bankLabel: String? = null, tail: String? = null) = TransactionEntity(
        id = id, amountPaise = amount, direction = dir, kind = kind, status = status,
        accountId = from, toAccountId = to, bankLabel = bankLabel, payerAccountLast4 = tail,
        timestampEvent = 2_000, timestampCaptured = 2_000, source = Source.MANUAL,
    )

    @Test fun `a transfer changes both balances but neither wealth nor expense totals`() {
        val transfer = row("move", 15_000, Direction.DEBIT, TxnKind.TRANSFER, "bank", "cash")
        val spend = row("cash-spend", 3_000, Direction.DEBIT, TxnKind.EXPENSE, "cash")
        val ledger = BalanceCalculator.ledger(listOf(bank, cash), listOf(transfer, spend), emptySet(), emptySet())
        assertEquals(85_000L, ledger.accounts.first().availablePaise)
        assertEquals(32_000L, ledger.accounts.last().availablePaise)
        assertEquals(117_000L, ledger.totalPaise)
        assertFalse(isSpend(transfer, emptySet(), emptySet()))
        assertTrue(isSpend(spend, emptySet(), emptySet()))
        assertEquals(0, ledger.unassignedCount)
    }

    @Test fun `two accounts at one bank require last-four or explicit assignment`() {
        val other = bank.copy(id = "other", accountLast4 = "9876")
        val ambiguous = row("ambiguous", 2_000, Direction.DEBIT, null, bankLabel = "HDFC")
        val exact = row("exact", 1_000, Direction.DEBIT, null, bankLabel = "HDFC Bank", tail = "9876")
        assertNull(AccountMatch.resolve(ambiguous, listOf(bank, other)))
        assertEquals("other", AccountMatch.resolve(exact, listOf(bank, other)))
        val balances = BalanceCalculator.ledger(listOf(bank, other), listOf(ambiguous, exact), emptySet(), emptySet())
        assertEquals(100_000L, balances.accounts[0].availablePaise)
        assertEquals(99_000L, balances.accounts[1].availablePaise)
        assertEquals(-2_000L, balances.unassignedPaise)
        assertEquals(197_000L, balances.totalPaise) // still includes the uncertain debit exactly once
    }

    @Test fun `linked UPI handles never add a second balance`() {
        val vpa = PaymentInstrumentEntity("instrument", "GPay", "me@okhdfc", "bank")
        val spend = row("one", 5_000, Direction.DEBIT, TxnKind.EXPENSE, "bank")
            .copy(instrumentId = vpa.id)
        val ledger = BalanceCalculator.ledger(listOf(bank), listOf(spend), emptySet(), emptySet(), listOf(vpa))
        assertEquals(95_000L, ledger.totalPaise)
        assertEquals(1, ledger.accounts.size)
    }

    @Test fun `card debt, pending payments and removed payments have explicit balance semantics`() {
        val card = BalanceAnchorEntity("card", "Credit card", -10_000, 1_000, AccountType.CARD)
        val pending = row("new-charge", 4_500, Direction.DEBIT, TxnKind.EXPENSE, "card", status = TxnStatus.PENDING)
        val failed = pending.copy(id = "failed", amountPaise = 9_000, status = TxnStatus.DISCARDED)
        val repayment = row("pay", 5_000, Direction.CREDIT, TxnKind.REPAYMENT, "card")
        val ledger = BalanceCalculator.ledger(listOf(card), listOf(pending, failed, repayment), emptySet(), emptySet())
        assertEquals(-9_500L, ledger.totalPaise)
        assertFalse(isSpend(repayment, emptySet(), emptySet()))
        assertFalse(isSpend(failed, emptySet(), emptySet()))
    }

    @Test fun `re-anchoring one account does not replay its old expenses or orphan its ID`() {
        val old = row("old", 7_000, Direction.DEBIT, TxnKind.EXPENSE, "bank")
        val bankNow = bank.copy(baselinePaise = 70_000, anchoredAt = 3_000)
        val new = old.copy(id = "new", amountPaise = 2_000, timestampEvent = 4_000)
        val ledger = BalanceCalculator.ledger(listOf(bankNow, cash), listOf(old, new), emptySet(), emptySet())
        assertEquals(68_000L, ledger.accounts.first().availablePaise)
        assertEquals(20_000L, ledger.accounts.last().availablePaise)
    }

    @Test fun `deleting an explicitly referenced account never charges another one`() {
        val payment = row("old-bank", 10_000, Direction.DEBIT, TxnKind.EXPENSE, "bank")
        val ledger = BalanceCalculator.ledger(listOf(cash), listOf(payment), emptySet(), emptySet())
        assertEquals(20_000L, ledger.totalPaise)
    }
}
