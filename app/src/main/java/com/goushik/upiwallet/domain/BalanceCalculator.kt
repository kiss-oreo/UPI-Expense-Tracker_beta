package com.goushik.upiwallet.domain

import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.PaymentInstrumentEntity
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnKind
import com.goushik.upiwallet.data.TxnStatus

object BalanceCalculator {

    fun isSelfTransfer(txn: TransactionEntity, ownVpas: Set<String>, ownNames: Set<String>): Boolean {
        // Explicit manual types win over a name/VPA heuristic; existing captured rows keep the
        // pre-2.0 behaviour until the owner resolves them in review.
        if (txn.kind != null) return txn.kind == TxnKind.TRANSFER
        val vpa = txn.payeeVpa?.lowercase()?.trim()
        if (vpa != null && vpa in ownVpas) return true
        val name = txn.payeeName?.lowercase()?.trim() ?: return false
        if (name.length < 4) return false
        val payee = words(name)
        return ownNames.any { own -> isOwnName(payee, own.lowercase().trim()) }
    }

    /**
     * Word-by-word match of a payee name against ONE own name. The confirm sheet often shows a short handle
     * or first name ("bram"), and a bank may shorten the surname to an initial ("bram s"), so the names are
     * lined up word by word, and the FIRST word must always be the same. Then either:
     *  - the payee's words are the START of the own name ("bram" for own "bram stoker"), where a later word
     *    may also be a single-letter initial of the other side's word, both ways: own "bram stoker" matches
     *    payee "bram s", and own "bram s" matches payee "bram stoker"; or
     *  - the payee's words START with the whole own name, word for word ("bram s" for own "bram"). Initials
     *    are not stretched this way, so "bram s traders" is not own "bram stoker".
     * Words, never raw characters: substring matching made "tarun" a self-transfer for own "arun" and
     * "kumar" one for own "sai kumar", silently dropping real payments out of every spend total.
     */
    private fun isOwnName(payee: List<String>, own: String): Boolean {
        val ownWords = words(own)
        if (ownWords.isEmpty() || payee.isEmpty()) return false
        if (payee[0] != ownWords[0]) return false
        if (payee.size <= ownWords.size) {
            return (1 until payee.size).all { sameWord(payee[it], ownWords[it]) }
        }
        // A tiny own name ("al") would claim every "al …" payee, so the widening direction needs 4+ chars.
        return own.length >= 4 && (1 until ownWords.size).all { payee[it] == ownWords[it] }
    }

    /** The same word, or one side is a single-letter initial of the other ("s" and "stoker"). */
    private fun sameWord(a: String, b: String): Boolean =
        a == b || (a.length == 1 && b.startsWith(a)) || (b.length == 1 && a.startsWith(b))

    private val WHITESPACE = Regex("\\s+")
    private fun words(s: String): List<String> = s.split(WHITESPACE).filter { it.isNotEmpty() }

    data class AccountBalance(val account: BalanceAnchorEntity, val availablePaise: Long)

    data class Ledger(
        val accounts: List<AccountBalance>,
        /** Money movement that could not safely be assigned to exactly one real account. It remains
         * visible in the combined total; the account list surfaces it separately until reviewed. */
        val unassignedPaise: Long,
        val unassignedCount: Int,
    ) {
        val totalPaise: Long get() = accounts.sumOf { it.availablePaise } + unassignedPaise
    }

    /** Live balances from stable account IDs, in integer paise. A manual transfer changes BOTH account
     * balances on its one row, but not the combined balance or spending. Re-anchoring an account resets
     * only its own ledger window. Captures without a unique account match are NOT silently charged to
     * every bank: they appear as a separate unassigned adjustment. The aggregate keeps the old global
     * cutoff for these rows, so upgrading 1.x never replays spending from before its last re-anchor.
     * PENDING/UNCONFIRMED debits are provisional; DISCARDED (failed/removed) ones do not move money. */
    fun ledger(
        anchors: List<BalanceAnchorEntity>,
        txns: List<TransactionEntity>,
        ownVpas: Set<String>,
        ownNames: Set<String>,
        instruments: List<PaymentInstrumentEntity> = emptyList(),
    ): Ledger {
        val balances = anchors.associate { it.id to it.baselinePaise }.toMutableMap()
        val byId = anchors.associateBy { it.id }
        val oldGlobalCutoff = anchors.maxOfOrNull { it.anchoredAt } ?: Long.MIN_VALUE
        var unassigned = 0L
        var unassignedCount = 0
        fun apply(id: String, delta: Long, at: Long) {
            val a = byId[id] ?: return
            if (at >= maxOf(a.anchoredAt, a.openedAt)) balances[id] = balances.getValue(id) + delta
        }
        for (t in txns) {
            if (t.status == TxnStatus.DISCARDED) continue
            if (t.kind == TxnKind.TRANSFER) {
                val from = t.accountId
                val to = t.toAccountId
                // Never book half a transfer as an expense/credit if an account was removed or corrupt.
                if (from != null && to != null && from != to && from in byId && to in byId) {
                    apply(from, -t.amountPaise, t.timestampEvent)
                    apply(to, t.amountPaise, t.timestampEvent)
                }
                continue
            }
            // Legacy a11y/SMS self-transfer pairs are excluded until manually reconciled: two
            // observed legs with the same RRN must never produce two separate balance movements.
            if (isSelfTransfer(t, ownVpas, ownNames)) continue
            val delta = if (t.direction == Direction.DEBIT) -t.amountPaise else t.amountPaise
            // An explicitly assigned account that was later deleted is history of that account,
            // not a new debit against the remaining accounts' unassigned bucket.
            if (t.accountId != null && t.accountId !in byId) continue
            val account = AccountMatch.resolve(t, anchors, instruments)
            if (account != null) apply(account, delta, t.timestampEvent)
            else if (t.timestampEvent >= oldGlobalCutoff) {
                unassigned += delta
                unassignedCount++
            }
        }
        return Ledger(anchors.map { AccountBalance(it, balances.getValue(it.id)) }, unassigned, unassignedCount)
    }

    fun available(
        anchors: List<BalanceAnchorEntity>,
        txns: List<TransactionEntity>,
        ownVpas: Set<String>,
        ownNames: Set<String>,
        instruments: List<PaymentInstrumentEntity> = emptyList(),
    ): Long = ledger(anchors, txns, ownVpas, ownNames, instruments).totalPaise

    /**
     * The timestamp to stamp a NEWLY-ADDED account with (the "Add account" flow — distinct from the
     * full "Update balance" re-anchor). A new account joins the existing snapshot at the accounts'
     * shared cutoff (`max(anchoredAt)`), so [available] sums the new baseline in but the global cutoff
     * NEVER moves forward — adding an account can only ADD its entered balance, never re-cut history and
     * inflate the existing accounts (the "totals must reconcile" guard). With no accounts yet, a fresh
     * [now] starts the first snapshot.
     */
    fun anchorStampFor(existing: List<BalanceAnchorEntity>, now: Long): Long =
        existing.maxOfOrNull { it.anchoredAt } ?: now

    /**
     * The anchor set after deleting the account [id] — the inverse "totals must reconcile" guard to
     * [anchorStampFor]. The cutoff in [available] is the GLOBAL `max(anchoredAt)`, so naively deleting
     * the anchor that holds it would slide the cutoff backward and pull already-reconciled history back
     * into the sum for every remaining account. Fix: if the victim held the unique max stamp, re-stamp
     * the latest remaining anchor to the old cutoff, so the window never moves and
     * `available(after) == available(before) − victim.baselinePaise`, exactly.
     * Returns null when [id] isn't in [existing] (nothing to delete).
     */
    fun anchorsAfterDelete(existing: List<BalanceAnchorEntity>, id: String): List<BalanceAnchorEntity>? {
        if (existing.none { it.id == id }) return null
        val remaining = existing.filter { it.id != id }
        if (remaining.isEmpty()) return remaining
        val oldCutoff = existing.maxOf { it.anchoredAt }
        val latest = remaining.maxByOrNull { it.anchoredAt }!!
        if (latest.anchoredAt == oldCutoff) return remaining
        return remaining.map { if (it.id == latest.id) it.copy(anchoredAt = oldCutoff) else it }
    }
}
