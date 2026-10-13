package com.goushik.upiwallet.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.data.BalanceAnchorEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.GhostButton
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.IconInfo
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Ids
import com.goushik.upiwallet.util.Money
import kotlinx.coroutines.launch

private fun moneyFilter(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }

/** Pre-fill string for a money field: rupees with thousands separators, + ".dd" only if non-zero paise.
 *  No ₹ symbol — the field's prefix carries it (mirrors what moneyFilter accepts). */
private fun prefillOf(paise: Long): String {
    val sign = if (paise < 0) "-" else ""
    val positive = kotlin.math.abs(paise)
    val rupees = "%,d".format(positive / 100)
    val frac = positive % 100
    return sign + if (frac == 0L) rupees else rupees + ".%02d".format(frac)
}

private class FieldState(val anchorId: String, val label: String, val availablePaise: Long, initial: String) {
    var text by mutableStateOf(initial)
}

/**
 * The wallet's "truth button" — re-anchor every account (USER-FLOW Part C). Full-screen over the
 * shared aurora canvas. The user opens their bank app, reads the real number, and types it in; on
 * Update we clear + re-write all anchors with a fresh timestamp, so BalanceCalculator counts only
 * spends after this moment and `available` snaps to the entered total.
 */
@Composable
fun UpdateBalanceScreen(onBack: () -> Unit, onAddAccount: () -> Unit) {
    BackHandler { onBack() }

    // Live (not one-shot) so a per-account delete re-seeds the form; null = first emission pending.
    val repo = ServiceLocator.repository
    val anchors by repo.observeAnchors().collectAsStateWithLifecycle(initialValue = null)
    val txns by repo.observeTransactions().collectAsStateWithLifecycle(emptyList())
    val profile by repo.observeProfile().collectAsStateWithLifecycle(null)
    val instruments by repo.observeInstruments().collectAsStateWithLifecycle(emptyList())
    val ledger = anchors?.let {
        BalanceCalculator.ledger(it, txns, profile?.ownVpaSet() ?: emptySet(),
            profile?.ownNameSet() ?: emptySet(), instruments)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack)
        when (val s = anchors) {
            null -> CenterNote("Loading…")
            else -> Body(s, ledger?.accounts?.associate { it.account.id to it.availablePaise }.orEmpty(), onBack, onAddAccount)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
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
        Text("Update balance", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
    }
}

@Composable
private fun Body(anchors: List<BalanceAnchorEntity>, live: Map<String, Long>, onBack: () -> Unit, onAddAccount: () -> Unit) {
    // Field state seeded from the loaded anchors (label + pre-filled rupee text); user edits live here.
    // Keyed on `anchors` so it re-seeds when they change (e.g. after a delete — unsaved edits in other
    // fields reset then, accepted); synchronous so the form never renders blank.
    val fields = remember(anchors, live) {
        mutableStateListOf<FieldState>().apply {
            anchors.forEach { a ->
                val amount = live[a.id] ?: a.baselinePaise
                add(FieldState(a.id, a.accountLabel, amount, prefillOf(amount)))
            }
        }
    }
    // The account whose ✕ was tapped — its inline "Remove?" strip is showing. Cleared on any change.
    var confirmingDelete by remember(anchors) { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(12.dp))
    Text("What's the real balance?", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
    Spacer(Modifier.height(10.dp))
    Text(
        "Open your bank app, read the actual balance, and type it in. The wallet snaps to it and tracks " +
            "every payment from here.",
        style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
    )
    Spacer(Modifier.height(24.dp))

    if (anchors.isEmpty()) {
        // No anchors yet (spend-only onboarding, or a skipped balance step) — re-anchoring needs an
        // account to write to, so route the user to "Add account" to bootstrap their first one.
        HintRow("No accounts yet — add your first below.")
        Spacer(Modifier.height(20.dp))
        GhostButton("+ Add account", onAddAccount)
        return
    }

    fields.forEachIndexed { i, f ->
        if (i > 0) Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FieldLabel(f.label) }
            Text(
                "✕",
                style = MaterialTheme.typography.labelLarge,
                color = TextTertiary,
                modifier = Modifier
                    .clip(WalletShapes.small)
                    .clickable { confirmingDelete = f.anchorId }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        WalletTextField(
            f.text, { f.text = moneyFilter(it) }, prefix = "₹", placeholder = "0",
            keyboardType = KeyboardType.Decimal, textStyle = MaterialTheme.typography.headlineMedium,
        )
        if (confirmingDelete == f.anchorId) {
            Spacer(Modifier.height(10.dp))
            DeleteConfirmStrip(
                label = f.label,
                baselinePaise = f.availablePaise,
                onKeep = { confirmingDelete = null },
                onRemove = {
                    confirmingDelete = null
                    deleteAccount(f.anchorId)
                },
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    HintRow("We never connect to your bank — this is just you telling us the truth.")

    Spacer(Modifier.height(24.dp))
    // Add a NEW account (additive, its own screen) — sits directly above the re-anchor "Update" button.
    GhostButton("+ Add account", onAddAccount)
    Spacer(Modifier.height(12.dp))
    val parsed = fields.map { Money.parsePaise(it.text) }
    val valid = fields.isNotEmpty() && parsed.all { it != null }
    PrimaryButton(
        "Update",
        onClick = {
            // Keep IDs, types and last-four; otherwise every previously assigned transaction and
            // linked UPI ID would become orphaned when the user simply refreshes their balance.
            val values = fields.associate { it.anchorId to Money.parsePaise(it.text)!! }
            ServiceLocator.appScope.launch {
                ServiceLocator.repository.reanchor(values, System.currentTimeMillis())
            }
            onBack()
        },
        enabled = valid,
    )
}

/** Inline two-step confirm (the app deliberately has no dialogs): what leaves the wallet, and that
 *  payment history is untouched. */
@Composable
private fun DeleteConfirmStrip(
    label: String,
    baselinePaise: Long,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().glassSurface(WalletShapes.medium, blur = false).padding(14.dp)) {
        Text(
            "Remove $label? Its ${Money.format(baselinePaise)} leaves your available balance. " +
                "Payments already recorded stay in your history.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "Keep",
                style = MaterialTheme.typography.labelLarge, color = TextSecondary,
                modifier = Modifier.clip(WalletShapes.small).clickable(onClick = onKeep)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Remove",
                style = MaterialTheme.typography.labelLarge, color = RedDebit,
                modifier = Modifier.clip(WalletShapes.small).clickable(onClick = onRemove)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Delete ONE account atomically: drop its anchor and, when it held the newest stamp, re-stamp the
 * latest remaining anchor to the old cutoff ([BalanceCalculator.anchorsAfterDelete]) — so Available
 * drops by exactly the removed baseline and the balance window never moves. Process-scoped so the
 * write survives the composable being swapped out.
 */
private fun deleteAccount(anchorId: String) {
    ServiceLocator.appScope.launch { ServiceLocator.repository.removeAccount(anchorId) }
}

@Composable
private fun HintRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconInfo(TextTertiary, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextTertiary)
    }
}
