package com.goushik.upiwallet.domain

import com.goushik.upiwallet.data.AccountType
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.PaymentInstrumentEntity
import com.goushik.upiwallet.data.TransactionEntity

/**
 * Maps a transaction's captured `bankLabel` to one of the user's set-up accounts (anchors). Needed because
 * the SAME bank shows up under different labels depending on the capture path — e.g. the SBI SMS parser
 * writes "SBI" while the GPay confirm-sheet parser captures "State Bank of India". We canonicalise both
 * sides to a short key before comparing, so per-account spend attribution doesn't silently undercount.
 */
object AccountMatch {

    /** Canonical short key for a bank label ("State Bank of India" / "SBI" → "sbi"). */
    fun normalizeBank(label: String): String {
        val l = label.lowercase()
        return when {
            "hdfc" in l -> "hdfc"
            "sbi" in l || "state bank" in l -> "sbi"
            "icici" in l -> "icici"
            "axis" in l -> "axis"
            "kotak" in l -> "kotak"
            "pnb" in l || "punjab national" in l -> "pnb"
            else -> l.trim()
        }
    }

    /** True when a transaction's captured bank label belongs to the given account (anchor) label. */
    fun matches(txnBankLabel: String?, accountLabel: String): Boolean =
        txnBankLabel != null && normalizeBank(txnBankLabel) == normalizeBank(accountLabel)

    /** No guessing between accounts at the same bank. An explicit user assignment (or a linked
     * instrument) wins. Captures are matched only when the bank + optional last four select exactly one
     * BANK; unresolved captures stay in the visible "Unassigned" ledger adjustment for review. A lone
     * account without any bank evidence is the one safe fallback for older 1.x captures. */
    fun resolve(
        txn: TransactionEntity,
        accounts: List<BalanceAnchorEntity>,
        instruments: List<PaymentInstrumentEntity> = emptyList(),
    ): String? {
        txn.accountId?.let { return it.takeIf { id -> accounts.any { a -> a.id == id } } }
        txn.instrumentId?.let { id ->
            return instruments.firstOrNull { it.id == id }?.accountId
                ?.takeIf { linked -> accounts.any { it.id == linked } }
        }
        val label = txn.bankLabel
        val last4 = txn.payerAccountLast4
        if (label == null && last4 == null) return accounts.singleOrNull()?.id
        val candidates = accounts.filter { a ->
            a.accountType == AccountType.BANK &&
                (label == null || matches(label, a.accountLabel)) &&
                (last4 == null || a.accountLast4 == null || last4 == a.accountLast4)
        }
        // Prefer an exact last-four match to an account with no stored tail, but never choose between
        // two accounts with the same tail (or two with no tail).
        if (last4 != null) {
            val exact = candidates.filter { it.accountLast4 == last4 }
            if (exact.size == 1) return exact.single().id
            if (exact.size > 1) return null
        }
        return candidates.singleOrNull()?.id
    }
}
