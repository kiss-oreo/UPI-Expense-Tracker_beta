package com.goushik.upiwallet.ui.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.data.Direction
import com.goushik.upiwallet.data.PaymentInstrumentEntity
import com.goushik.upiwallet.data.TxnKind
import com.goushik.upiwallet.data.MerchantRuleEntity
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.data.TransactionEntity
import com.goushik.upiwallet.data.TxnStatus
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.categorize.Categorization
import com.goushik.upiwallet.domain.categorize.Category
import com.goushik.upiwallet.domain.categorize.Categorizer
import com.goushik.upiwallet.domain.insights.PickerDate
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.FieldBg
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.White
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Ids
import com.goushik.upiwallet.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun moneyFilter(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' }

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")
private val zone: ZoneId get() = ZoneId.systemDefault()

/** A date-picker value is 00:00 UTC of the picked day, so it is read through [PickerDate], never the zone. */
private fun fmtDate(pickerMs: Long): String = PickerDate.toLocalDate(pickerMs).format(DATE_FMT)

/**
 * The category chips a manual entry offers. "Transfer-to-self" is left out, exactly as on the detail
 * screen: totals decide self-transfers from the owner's own UPI IDs and name, never from this label, so a
 * manual "Transfer-to-self" payment would still count as spending — and teach that label to the payee.
 */
internal val ManualCategoryChoices: List<Category> = Category.entries.filter { it != Category.SELF_TRANSFER }

/** Days after today can't be picked: a payment being logged has already happened, and a future-dated row
 *  would sit at the top of Recent, counted in no total, until its date came round. */
@OptIn(ExperimentalMaterial3Api::class)
private object NotAfterToday : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        PickerDate.isOnOrBeforeToday(utcTimeMillis, System.currentTimeMillis(), zone)

    override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now(zone).year
}

/**
 * Manual transaction entry — opened from the raised "+" in the bottom nav. Full-screen over the shared
 * aurora canvas (the same swap idiom as Update-balance / Edit-profile), so it owns its own BackHandler.
 * On Add it writes a CONFIRMED, source="manual" [TransactionEntity] (no RRN), then either records the
 * chosen category as a manual fix + learned rule (mirroring the detail screen) or, if none was picked,
 * lets the deterministic [Categorization] sweep tag it from the payee.
 */
@Composable
fun AddTransactionScreen(onBack: () -> Unit, editingId: String? = null) {
    BackHandler { onBack() }

    val accounts by ServiceLocator.repository.observeAnchors().collectAsStateWithLifecycle(emptyList())
    val instruments by ServiceLocator.repository.observeInstruments().collectAsStateWithLifecycle(emptyList())
    val editing by produceState<TransactionEntity?>(null, editingId) {
        if (editingId != null) value = ServiceLocator.repository.transactionById(editingId)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack, editingId != null)
        if (editingId == null || editing != null) Body(accounts, instruments, onBack, editing)
        else Text("Loading transaction…", color = TextSecondary)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, editing: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).glassSurface(WalletShapes.medium, blur = false).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            IconChevronLeft(TextPrimary, size = 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        Text(if (editing) "Edit transaction" else "Add transaction",
            style = MaterialTheme.typography.titleLarge, color = TextPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Body(accounts: List<BalanceAnchorEntity>, instruments: List<PaymentInstrumentEntity>,
                 onBack: () -> Unit, initial: TransactionEntity?) {
    val context = LocalContext.current
    val key = initial?.id
    var kind by remember(key) { mutableStateOf(initial?.kind ?:
        if (initial?.direction == Direction.CREDIT) TxnKind.INCOME else TxnKind.EXPENSE) }
    var direction by remember(key) { mutableStateOf(initial?.direction ?: Direction.DEBIT) }
    var amount by remember(key) { mutableStateOf(initial?.amountPaise?.let { "${it / 100}.%02d".format(it % 100) }.orEmpty()) }
    var payee by remember(key) { mutableStateOf(initial?.payeeName.orEmpty()) }
    var note by remember(key) { mutableStateOf(initial?.note.orEmpty()) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    // Picker values are UTC midnight of the local date, NOT the original event instant.
    var dateMs by remember(key) { mutableStateOf(initial?.let {
        PickerDate.of(Instant.ofEpochMilli(it.timestampEvent).atZone(zone).toLocalDate())
    }) }
    var dateChanged by remember(key) { mutableStateOf(false) }
    var accountId by remember(key) { mutableStateOf(initial?.accountId) }
    var toAccountId by remember(key) { mutableStateOf(initial?.toAccountId) }
    var instrumentId by remember(key) { mutableStateOf(initial?.instrumentId) }
    var category by remember(key) { mutableStateOf(Category.fromLabel(initial?.category)) }
    var pickingDate by remember { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    FieldLabel("Type")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            TxnKind.EXPENSE to "Spent", TxnKind.INCOME to "Received",
            TxnKind.TRANSFER to "Transfer", TxnKind.REFUND to "Refund",
            TxnKind.REPAYMENT to "Debt repayment",
        ).forEach { (option, title) ->
            SelectChip(title, kind == option) {
                kind = option
                direction = if (option == TxnKind.INCOME || option == TxnKind.REFUND) Direction.CREDIT else Direction.DEBIT
                instrumentId = null
            }
        }
    }
    if (kind == TxnKind.REPAYMENT) {
        Spacer(Modifier.height(12.dp))
        DirectionToggle(direction) { direction = it }
        Text("Paying or collecting an existing debt does not add a new expense or income.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
    if (kind == TxnKind.TRANSFER) {
        Spacer(Modifier.height(10.dp))
        Text("Moves money between two accounts. Never counted as spending.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }

    Spacer(Modifier.height(22.dp))
    FieldLabel("Amount")
    WalletTextField(
        amount, { amount = moneyFilter(it) }, prefix = "₹", placeholder = "0",
        keyboardType = KeyboardType.Decimal, textStyle = MaterialTheme.typography.headlineMedium,
    )

    if (kind != TxnKind.TRANSFER) {
        Spacer(Modifier.height(18.dp))
        FieldLabel(if (direction == Direction.DEBIT) "Paid to" else "Received from", "optional")
        WalletTextField(
            payee, { payee = it },
            placeholder = if (direction == Direction.DEBIT) "e.g. Swiggy" else "e.g. Rohan",
            textStyle = MaterialTheme.typography.titleLarge,
        )
    }

    Spacer(Modifier.height(18.dp))
    FieldLabel("When")
    PickerField(dateMs?.let(::fmtDate) ?: "Today") { pickingDate = true }

    if (accounts.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        FieldLabel(if (kind == TxnKind.TRANSFER) "From account" else "Account")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            accounts.forEach { a ->
                SelectChip(a.accountLabel + (a.accountLast4?.let { " ••$it" } ?: ""), accountId == a.id) {
                    accountId = a.id
                    instrumentId = null
                }
            }
        }
        if (kind == TxnKind.TRANSFER) {
            Spacer(Modifier.height(14.dp))
            FieldLabel("To account")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.filter { it.id != accountId }.forEach { a ->
                    SelectChip(a.accountLabel + (a.accountLast4?.let { " ••$it" } ?: ""), toAccountId == a.id) {
                        toAccountId = a.id
                    }
                }
            }
        } else {
            val linked = instruments.filter { it.accountId == accountId }
            if (linked.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FieldLabel("Paid via", "optional — same account, not another balance")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    linked.forEach { item ->
                        SelectChip(item.label, instrumentId == item.id) {
                            instrumentId = if (instrumentId == item.id) null else item.id
                        }
                    }
                }
            }
        }
    } else {
        Text("No accounts set up yet. This entry will be unassigned until you add one.",
            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }

    if (kind == TxnKind.EXPENSE) {
        Spacer(Modifier.height(18.dp))
        FieldLabel("Category", "optional — we'll guess if you skip it")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ManualCategoryChoices.forEach { cat ->
                SelectChip(cat.label, category == cat) { category = if (category == cat) null else cat }
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    FieldLabel("Note", "optional; kept only on this phone")
    WalletTextField(note, { note = it.take(500) }, placeholder = "What was this for?", singleLine = false)
    error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = RedDebit)
    }

    Spacer(Modifier.height(28.dp))
    val paise = Money.parsePaise(amount)
    val valid = paise != null && paise > 0 && (accounts.isEmpty() || accountId != null) &&
        (kind != TxnKind.TRANSFER || (accountId != null && toAccountId != null && accountId != toAccountId))
    PrimaryButton(
        if (initial != null) "Save changes" else if (kind == TxnKind.TRANSFER) "Transfer money" else "Add transaction",
        enabled = valid,
        onClick = {
            val amt = paise ?: return@PrimaryButton
            val selectedKind = kind
            val chosen = category.takeIf { selectedKind == TxnKind.EXPENSE }
            val selected = accounts.firstOrNull { it.id == accountId }
            val destination = accounts.firstOrNull { it.id == toAccountId }
            val picked = dateMs
            val draft = TransactionEntity(
                id = initial?.id ?: Ids.uuid7(), amountPaise = amt, direction = direction,
                status = initial?.status ?: TxnStatus.CONFIRMED,
                payeeName = if (selectedKind == TxnKind.TRANSFER) destination?.accountLabel else payee.trim().ifBlank { null },
                timestampEvent = if (initial != null && !dateChanged) initial.timestampEvent else
                    PickerDate.eventMsFor(picked, System.currentTimeMillis(), zone),
                timestampCaptured = initial?.timestampCaptured ?: System.currentTimeMillis(), source = Source.MANUAL,
                needsReview = false, bankLabel = selected?.accountLabel, kind = selectedKind,
                accountId = accountId, toAccountId = if (selectedKind == TxnKind.TRANSFER) toAccountId else null,
                instrumentId = if (selectedKind == TxnKind.TRANSFER) null else instrumentId,
                note = note.trim().ifBlank { null }, category = chosen?.label,
                categoryConfidence = if (chosen != null) 1.0f else null,
                categorySource = if (chosen != null) "manual" else null,
            )
            ServiceLocator.appScope.launch {
                val repo = ServiceLocator.repository
                runCatching { check(repo.saveManual(draft)) { "Payment was already removed." } }
                    .onSuccess {
                        // Auxiliary learning/notifications may fail; the entry is already saved.
                        runCatching {
                            if (chosen != null) {
                                Categorizer.matchKey(draft)?.let { key ->
                                    repo.upsertMerchantRule(MerchantRuleEntity(key, chosen.label, "user", draft.timestampCaptured))
                                }
                            } else Categorization.run(repo)
                        }
                        runCatching { com.goushik.upiwallet.domain.budget.BudgetAlerts.check(context) }
                        withContext(Dispatchers.Main) { onBack() }
                    }
                    .onFailure { withContext(Dispatchers.Main) { error = it.message ?: "Couldn't save transaction" } }
            }
        },
    )

    if (pickingDate) {
        val dpState = rememberDatePickerState(
            // Seed with TODAY as a picker value — raw "now" pre-selects yesterday in India before 05:30.
            initialSelectedDateMillis = dateMs ?: PickerDate.today(System.currentTimeMillis(), zone),
            selectableDates = NotAfterToday,
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                val sel = dpState.selectedDateMillis
                TextButton(
                    enabled = sel != null,
                    onClick = { if (sel != null) { dateMs = sel; dateChanged = true }; pickingDate = false },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(
                state = dpState,
                title = {
                    Text(
                        "Select date",
                        modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                    )
                },
            )
        }
    }
}

// ── pieces ──

@Composable
private fun DirectionToggle(direction: Direction, onChange: (Direction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(PillShape).background(FieldBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleHalf("Paid back", direction == Direction.DEBIT, Modifier.weight(1f)) { onChange(Direction.DEBIT) }
        ToggleHalf("Received back", direction == Direction.CREDIT, Modifier.weight(1f)) { onChange(Direction.CREDIT) }
    }
}

@Composable
private fun ToggleHalf(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(PillShape)
            .background(if (selected) Violet500.copy(alpha = 0.30f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) White else TextSecondary,
        )
    }
}

/** A tappable read-only field (looks like WalletTextField) that opens a picker. */
@Composable
private fun PickerField(value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(WalletShapes.medium).background(FieldBg)
            .border(1.dp, HairlineColor, WalletShapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(PillShape)
            .background(if (selected) Violet500.copy(alpha = 0.22f) else FieldBg)
            .border(1.dp, if (selected) Violet500 else HairlineColor, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) White else TextSecondary,
        )
    }
}
