package com.goushik.upiwallet.data

import androidx.room.withTransaction
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.util.Ids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The on-device source of truth. Exposes persistence primitives + a race-safe transactional entry
 * point; the Reconciler (async dedup) and BalanceCalculator build on top of these.
 */
class TransactionRepository(val db: AppDatabase) {
    private val txnDao = db.transactionDao()
    private val rawDao = db.rawEventDao()
    private val anchorDao = db.balanceAnchorDao()
    private val profileDao = db.userProfileDao()
    private val merchantRuleDao = db.merchantRuleDao()
    private val budgetDao = db.budgetDao()
    private val categoryBudgetDao = db.categoryBudgetDao()
    private val instrumentDao = db.paymentInstrumentDao()

    fun observeTransactions(): Flow<List<TransactionEntity>> = txnDao.observeAll()
    /** One-shot read for non-reactive callers (the home-screen widget). */
    suspend fun transactions(): List<TransactionEntity> = txnDao.allOnce()
    fun observeReviewTransactions(): Flow<List<TransactionEntity>> = txnDao.observeNeedsReview()
    fun observeAnchors(): Flow<List<BalanceAnchorEntity>> = anchorDao.observeAll()
    fun observeProfile(): Flow<UserProfileEntity?> = profileDao.observe()

    suspend fun anchors(): List<BalanceAnchorEntity> = anchorDao.all()
    suspend fun anchorCount(): Int = anchorDao.count()
    suspend fun upsertAnchor(a: BalanceAnchorEntity) = anchorDao.upsert(a)
    suspend fun addAccount(label: String, type: AccountType, balancePaise: Long, last4: String?): String = inTransaction {
        val name = label.trim()
        val tail = last4?.trim()?.ifBlank { null }
        require(name.isNotEmpty())
        require(tail == null || (tail.length == 4 && tail.all(Char::isDigit)))
        val old = anchorDao.all()
        require(old.none {
            it.accountLabel.trim().equals(name, ignoreCase = true) && it.accountType == type &&
                (it.accountLast4 == tail || it.accountLast4 == null || tail == null)
        }) { "An account with that name already exists. Add distinct last four digits." }
        val now = System.currentTimeMillis()
        val id = Ids.uuid7()
        anchorDao.upsert(BalanceAnchorEntity(id, name, balancePaise,
            BalanceCalculator.anchorStampFor(old, now), type, tail, openedAt = now))
        id
    }
    suspend fun clearAnchors() = anchorDao.clear()
    suspend fun deleteAnchor(id: String) = anchorDao.deleteById(id)

    suspend fun profile(): UserProfileEntity? = profileDao.get()
    suspend fun upsertProfile(p: UserProfileEntity) = profileDao.upsert(p)

    // ── Categorization (Phase 3) ──
    suspend fun uncategorizedTransactions(): List<TransactionEntity> = txnDao.uncategorized()
    suspend fun setCategory(id: String, category: String, conf: Float, source: String, needsReview: Boolean): Int =
        txnDao.setCategoryIfUncategorized(id, category, conf, source, needsReview)
    suspend fun setCategoryManual(id: String, category: String): Int = txnDao.setCategoryManual(id, category)
    suspend fun clearNeedsReview(ids: List<String>): Int = txnDao.clearNeedsReview(ids)
    /** Mark a row DISCARDED (Review "Remove this copy"): drops it from the queue + all totals; raw kept. */
    suspend fun discard(id: String): Int = txnDao.discard(id)
    suspend fun merchantRules(): List<MerchantRuleEntity> = merchantRuleDao.all()
    suspend fun upsertMerchantRule(rule: MerchantRuleEntity) = merchantRuleDao.upsert(rule)

    // ── Budgets (Phase 2) ──
    fun observeBudgets(): Flow<List<BudgetEntity>> = budgetDao.observeAll()
    suspend fun budgets(): List<BudgetEntity> = budgetDao.all()
    suspend fun upsertBudget(b: BudgetEntity) = budgetDao.upsert(b)
    suspend fun deleteBudget(period: String) = budgetDao.delete(period)
    suspend fun markBudgetAlerted(period: String, threshold: Int, periodStart: Long) =
        budgetDao.markAlerted(period, threshold, periodStart)

    fun observeCategoryBudgets(): Flow<List<CategoryBudgetEntity>> = categoryBudgetDao.observeAll()
    suspend fun categoryBudgets(): List<CategoryBudgetEntity> = categoryBudgetDao.all()
    suspend fun upsertCategoryBudget(b: CategoryBudgetEntity) = categoryBudgetDao.upsert(b)
    suspend fun deleteCategoryBudget(category: String) = categoryBudgetDao.delete(category)
    suspend fun markCategoryBudgetAlerted(category: String, threshold: Int, periodStart: Long) =
        categoryBudgetDao.markAlerted(category, threshold, periodStart)

    fun observeInstruments(): Flow<List<PaymentInstrumentEntity>> = instrumentDao.observeAll()
    suspend fun instruments(): List<PaymentInstrumentEntity> = instrumentDao.all()
    suspend fun upsertInstrument(item: PaymentInstrumentEntity) = inTransaction {
        require(anchorDao.all().any { it.id == item.accountId }) { "Choose an existing account." }
        require(item.vpa.contains('@') && item.label.isNotBlank()) { "Enter a valid UPI ID and name." }
        instrumentDao.upsert(item.copy(vpa = item.vpa.trim().lowercase(), label = item.label.trim()))
    }
    suspend fun deleteInstrument(id: String) = instrumentDao.delete(id)

    /** Re-anchor *in place* so transaction/instrument IDs keep pointing to the same account. */
    suspend fun reanchor(values: Map<String, Long>, now: Long) = inTransaction {
        val current = anchorDao.all()
        require(values.keys == current.mapTo(HashSet()) { it.id })
        current.forEach { a -> anchorDao.upsert(a.copy(baselinePaise = values.getValue(a.id), anchoredAt = now)) }
    }

    suspend fun removeAccount(id: String) = inTransaction {
        val before = anchorDao.all()
        val after = BalanceCalculator.anchorsAfterDelete(before, id) ?: return@inTransaction
        anchorDao.deleteById(id)
        instrumentDao.deleteForAccount(id)
        after.filter { a -> before.first { it.id == a.id }.anchoredAt != a.anchoredAt }
            .forEach { anchorDao.upsert(it) }
    }

    /** Manual rows may be changed without racing a bank SMS. Captured rows use [setAttribution]
     * instead, which touches ONLY user fields and cannot overwrite reconciliation proof. */
    suspend fun saveManual(t: TransactionEntity): Boolean = inTransaction {
        require(t.source == Source.MANUAL && t.rrn == null && t.amountPaise > 0L)
        validateAccounts(t)
        val old = txnDao.byId(t.id)
        if (old == null) {
            txnDao.insert(t) != -1L
        } else if (old.source == Source.MANUAL && old.status != TxnStatus.DISCARDED) {
            txnDao.update(old.copy(
                amountPaise = t.amountPaise, direction = t.direction, kind = t.kind,
                payeeName = t.payeeName?.trim(), bankLabel = t.bankLabel,
                timestampEvent = t.timestampEvent, accountId = t.accountId, toAccountId = t.toAccountId,
                instrumentId = t.instrumentId, category = t.category, categoryConfidence = t.categoryConfidence,
                categorySource = t.categorySource, note = t.note?.trim(),
            ))
            true
        } else false
    }

    /** One balanced row. Transfers neither change the combined available balance nor spending. */
    suspend fun transfer(from: String, to: String, amountPaise: Long, note: String? = null): String {
        val now = System.currentTimeMillis()
        val id = Ids.uuid7()
        val txn = TransactionEntity(
            id = id, amountPaise = amountPaise, direction = Direction.DEBIT, status = TxnStatus.CONFIRMED,
            timestampEvent = now, timestampCaptured = now, source = Source.MANUAL, kind = TxnKind.TRANSFER,
            accountId = from, toAccountId = to, note = note?.trim(),
        )
        check(saveManual(txn)) { "Couldn't record transfer." }
        return id
    }

    private suspend fun validateAccounts(t: TransactionEntity) {
        val ids = anchorDao.all().mapTo(HashSet()) { it.id }
        require(t.accountId == null || t.accountId in ids) { "Account was removed. Pick another." }
        if (t.kind == TxnKind.TRANSFER) {
            require(t.direction == Direction.DEBIT && t.accountId != null &&
                t.toAccountId != null && t.toAccountId in ids && t.accountId != t.toAccountId) {
                "Choose two different accounts for a transfer."
            }
            val a = anchorDao.all().filter { it.id == t.accountId || it.id == t.toAccountId }
            require(a.all { t.timestampEvent >= maxOf(it.anchoredAt, it.openedAt) }) {
                "Transfers before an account's starting balance can't be recorded."
            }
        } else {
            require(t.toAccountId == null) { "Only transfers have a destination account." }
            require(when (t.kind) {
                TxnKind.INCOME, TxnKind.REFUND -> t.direction == Direction.CREDIT
                TxnKind.EXPENSE -> t.direction == Direction.DEBIT
                TxnKind.REPAYMENT -> true
                TxnKind.TRANSFER, null -> false
            }) { "Transaction type doesn't match its direction." }
        }
        if (t.instrumentId != null) {
            require(instrumentDao.all().any { it.id == t.instrumentId && it.accountId == t.accountId }) {
                "That UPI ID is not linked to this account."
            }
        }
    }

    suspend fun setAttribution(id: String, accountId: String?, instrumentId: String?, note: String?) = inTransaction {
        if (accountId != null) require(anchorDao.all().any { it.id == accountId })
        if (instrumentId != null) require(instrumentDao.all().any { it.id == instrumentId && it.accountId == accountId })
        txnDao.updateAttribution(id, accountId, instrumentId, note?.trim())
    }

    suspend fun markCaptureKind(id: String, kind: TxnKind) = inTransaction {
        val row = txnDao.byId(id) ?: return@inTransaction
        require(when (kind) {
            TxnKind.EXPENSE -> row.direction == Direction.DEBIT
            TxnKind.INCOME, TxnKind.REFUND -> row.direction == Direction.CREDIT
            else -> false
        }) { "This type doesn't match the captured direction." }
        txnDao.markCaptureKind(id, kind)
    }

    suspend fun insertRaw(e: RawEventEntity) = rawDao.insert(e)
    suspend fun insertTransaction(t: TransactionEntity): Long = txnDao.insert(t)
    suspend fun transactionById(id: String) = txnDao.byId(id)
    fun observeTransactionById(id: String): Flow<TransactionEntity?> = txnDao.observeById(id)
    suspend fun rawEventsFor(txnId: String): List<RawEventEntity> = rawDao.forTxn(txnId)

    /** Run find→claim→merge atomically (single SQLite writer serializes concurrent reconcilers). */
    suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction(block)

    // ── Review tools: removing, undoing and putting back ──

    /** Removed rows, newest first (the future "Removed payments" list). */
    fun observeRemovedTransactions(): Flow<List<TransactionEntity>> = txnDao.observeDiscarded()

    /** txn id → package of the UPI app whose screen created it (a11y captures only). */
    fun observeCaptureApps(): Flow<Map<String, String>> = txnDao.observeCaptureApps().map { it.toAppMap() }
    suspend fun captureApps(): Map<String, String> = txnDao.captureApps().toAppMap()

    /**
     * Remove a row and hand back the status it had, so an Undo can restore exactly that. Null when the row
     * is gone or was already removed (nothing to undo). Atomic, so the status read is the one replaced.
     */
    suspend fun removeForUndo(id: String): TxnStatus? = inTransaction {
        val before = txnDao.byId(id)?.status
        if (before == null || before == TxnStatus.DISCARDED) {
            null
        } else {
            txnDao.discard(id)
            before
        }
    }

    /**
     * Restore a removed row to [status] — the caller decides it with domain/review/RemovedPayments (Undo:
     * what it was; Put back: derived from the row). No-op unless the row is still removed, and an RRN
     * always brings it back CONFIRMED (see [TransactionDao.restoreDiscarded]).
     */
    suspend fun restoreRemoved(id: String, status: TxnStatus): Int = txnDao.restoreDiscarded(id, status)

    /** Soft-remove many rows at once (reversible one by one with [restoreRemoved]). Chunked to stay under
     *  SQLite's bound-variable limit. */
    suspend fun removeAll(ids: Collection<String>): Int = inTransaction {
        ids.distinct().chunked(500).sumOf { txnDao.discardAll(it) }
    }

    private fun List<CaptureApp>.toAppMap(): Map<String, String> {
        val out = HashMap<String, String>(size)
        for (c in this) {
            val txnId = c.txnId ?: continue
            val pkg = c.packageName ?: continue
            out.putIfAbsent(txnId, pkg)   // a row has one screen capture; the first one wins if ever two
        }
        return out
    }
}
