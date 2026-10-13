package com.goushik.upiwallet.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.goushik.upiwallet.ui.theme.WarnColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.domain.HealthStore
import com.goushik.upiwallet.ui.common.CaptureDownBanner
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.IconChevronDown
import com.goushik.upiwallet.ui.common.TogglePill
import com.goushik.upiwallet.ui.common.rememberCaptureGrants
import com.goushik.upiwallet.ui.common.rememberBackupRestorePicker
import com.goushik.upiwallet.ui.nav.BottomNavHeight
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.PillShape
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Backup
import com.goushik.upiwallet.util.CsvExport
import com.goushik.upiwallet.util.DateTime
import com.goushik.upiwallet.util.Money
import com.goushik.upiwallet.util.Permissions
import com.goushik.upiwallet.util.upiIdsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings & health tab (USER-FLOW §5). Read-only readout + deep-links; AppShell draws the aurora and
 * hosts the bottom nav, so this is just HomeContent's LazyColumn — no WalletBackground/systemBarsPadding.
 * Mutating flows (update balance, edit profile) route out via callbacks; CSV export is a one-shot.
 */
@Composable
fun SettingsScreen(
    onUpdateBalance: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenLocation: () -> Unit,
    onOpenBudgets: () -> Unit,
    onLinkUpi: () -> Unit = {},
    onOpenRemoved: () -> Unit = {},
) {
    val repo = ServiceLocator.repository
    val removed by repo.observeRemovedTransactions().collectAsStateWithLifecycle(emptyList())
    val backupStatus by Backup.observeStatus(LocalContext.current).collectAsStateWithLifecycle()
    val txns by repo.observeTransactions().collectAsStateWithLifecycle(emptyList())
    val anchors by repo.observeAnchors().collectAsStateWithLifecycle(emptyList())
    val profile by repo.observeProfile().collectAsStateWithLifecycle(null)
    val budgets by repo.observeBudgets().collectAsStateWithLifecycle(emptyList())
    val categoryBudgets by repo.observeCategoryBudgets().collectAsStateWithLifecycle(emptyList())
    val instruments by repo.observeInstruments().collectAsStateWithLifecycle(emptyList())
    val (grants, _) = rememberCaptureGrants()
    val ctx = LocalContext.current

    val restoreLauncher = rememberBackupRestorePicker()
    var showEncryptedDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportConfirm by remember { mutableStateOf("") }
    var exportSecret by remember { mutableStateOf<CharArray?>(null) }
    var plaintextAction by remember { mutableStateOf<String?>(null) }
    var autoPlaintext by remember { mutableStateOf(Backup.autoBackupEnabled(ctx)) }
    val encryptedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val secret = exportSecret
        exportSecret = null
        if (uri != null && secret != null) {
            ServiceLocator.appScope.launch {
                val result = try {
                    runCatching { Backup.writeEncryptedToUri(ctx, ServiceLocator.db, uri, secret) }
                } finally { secret.fill('\u0000') }
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, if (result.isSuccess) "Encrypted backup saved. Keep your password safe — it cannot be recovered."
                        else result.exceptionOrNull()?.message ?: "Couldn't save the encrypted backup", Toast.LENGTH_LONG).show()
                }
            }
        } else secret?.fill('\u0000')
    }

    if (showEncryptedDialog) {
        AlertDialog(
            onDismissRequest = { showEncryptedDialog = false; exportPassword = ""; exportConfirm = "" },
            title = { Text("Create encrypted backup") },
            text = {
                Column {
                    Text("Choose a password of at least 8 characters. There is no password recovery. " +
                        "Your chosen file location may sync if it is a cloud provider.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(exportPassword, { exportPassword = it }, label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(exportConfirm, { exportConfirm = it }, label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = exportPassword.length >= 8 && exportPassword == exportConfirm,
                    onClick = {
                        exportSecret = exportPassword.toCharArray()
                        exportPassword = ""; exportConfirm = ""; showEncryptedDialog = false
                        encryptedLauncher.launch("UET-private-backup-${java.time.LocalDate.now()}.json")
                    }) { Text("Choose location") }
            },
            dismissButton = { TextButton(onClick = {
                showEncryptedDialog = false; exportPassword = ""; exportConfirm = ""
            }) { Text("Cancel") } },
        )
    }
    if (plaintextAction != null) {
        AlertDialog(
            onDismissRequest = { plaintextAction = null },
            title = { Text("Unencrypted backup") },
            text = { Text("The public Downloads file contains your payment history, captured screen " +
                "text, bank SMS and optional locations. Other apps with file access or a connected " +
                "computer can read it. Old unencrypted files remain until you delete them yourself. " +
                "Use an encrypted backup instead if you need to keep this private.") },
            confirmButton = {
                TextButton(onClick = {
                    val action = plaintextAction
                    plaintextAction = null
                    if (action == "auto") {
                        autoPlaintext = true
                        Backup.setAutoBackupEnabled(ctx, true)
                    }
                    ServiceLocator.appScope.launch {
                        val result = runCatching { Backup.writeToDownloads(ctx, ServiceLocator.db) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, result.fold(
                                onSuccess = { Backup.savedMessage(it.fileName, it.keptOlder) },
                                onFailure = { "Couldn't save the backup. Any earlier file is unchanged." },
                            ), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(if (plaintextAction == "auto") "Enable" else "Save unencrypted") }
            },
            dismissButton = { TextButton(onClick = { plaintextAction = null }) { Text("Cancel") } },
        )
    }

    val ledger = BalanceCalculator.ledger(anchors, txns,
        ownVpas = profile?.ownVpaSet() ?: emptySet(),
        ownNames = profile?.ownNameSet() ?: emptySet(), instruments = instruments)
    val available = ledger.totalPaise
    // Post-onboarding there is always a profile + ≥1 anchor; the flows' initial (null / empty) values
    // just mean Room hasn't emitted yet. Guard so entry never flashes a wrong (even negative) balance.
    val ready = profile != null && anchors.isNotEmpty()
    val showBalance = profile?.showBalance ?: true
    val lastChecked = remember(grants) { HealthStore(ctx).lastCheckedAt() }
    // "Healthy" only says the switch is on; this says whether payments are actually being recorded.
    // After an upgrade the recorder has no stamp until the next payment, so fall back to the newest screen
    // capture already in the ledger rather than claiming nothing was ever recorded.
    val newestScreenCapture = remember(txns) {
        txns.filter { it.source.contains("a11y") }.maxOfOrNull { it.timestampCaptured } ?: 0L
    }
    val liveness = remember(grants, newestScreenCapture) {
        com.goushik.upiwallet.domain.CaptureWatch.liveness(ctx).let {
            it.copy(lastQualifiedAt = maxOf(it.lastQualifiedAt, newestScreenCapture))
        }
    }
    val livenessNow = System.currentTimeMillis()
    val livenessQuiet = liveness.unmatchedLabel()
    val confirmedRemoved = remember(removed, txns) {
        com.goushik.upiwallet.ui.removed.RemovedList.confirmedIds(removed, txns).size
    }
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "1.0"
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = topInset + 8.dp,
            bottom = BottomNavHeight + bottomInset + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineLarge, color = TextPrimary) }

        if (grants.capturePaused) {
            item {
                CaptureDownBanner(onFix = { Permissions.openAccessibilitySettings(ctx) }, stuck = grants.captureStuck)
            }
        }

        // ── Capture health ──
        item {
            SettingsSection("Capture health") {
                SettingsCard {
                    // Same "paused" as the widgets and the reminder (CaptureWatch.isPaused), not the raw grant.
                    HealthRow(
                        "Accessibility (capture)", !grants.capturePaused, first = true,
                        subtitle = if (livenessQuiet != null) "${liveness.label(livenessNow)} · $livenessQuiet"
                        else liveness.label(livenessNow),
                        subtitleWarn = livenessQuiet != null,
                        onClick = { Permissions.openAccessibilitySettings(ctx) },
                    )
                    HealthRow(
                        "Bank SMS", grants.sms,
                        onClick = { ctx.startActivity(Permissions.appDetails(ctx)) },
                    )
                    HealthRow(
                        "Battery unrestricted", grants.battery,
                        onClick = { ctx.startActivity(Permissions.batteryExemption(ctx)) },
                    )
                }
                if (lastChecked > 0L) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Checked ${DateTime.ago(lastChecked)}",
                        style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }

        // ── Wallet mode (Phase C) ──
        item {
            val fancyCard by ServiceLocator.uiPrefs.fancyCard.collectAsStateWithLifecycle()
            SettingsSection("Wallet") {
                SettingsCard {
                    ToggleRow(
                        "Show account balance",
                        if (showBalance) "Your available-balance wallet is on" else "Off — just your spending",
                        on = showBalance, first = true,
                        onToggle = { newVal ->
                            profile?.let { p ->
                                ServiceLocator.appScope.launch { repo.upsertProfile(p.copy(showBalance = newVal)) }
                            }
                        },
                    )
                    ToggleRow(
                        "Fancy card",
                        if (fancyCard) "3D tilt, shine and the card flip" else "Off — a simple glass card",
                        on = fancyCard,
                        onToggle = { ServiceLocator.uiPrefs.setFancyCard(it) },
                    )
                }
            }
        }

        // ── Balance (dormant + dimmed in spend-only mode). Adding an account lives on Update balance now. ──
        item {
            SettingsSection("Balance") {
                Box(Modifier.alpha(if (showBalance) 1f else 0.45f)) {
                    SettingsCard {
                        SettingsRow("Available", if (ready) Money.format(available) else "—", first = true)
                        ledger.accounts.forEach { row ->
                            val a = row.account
                            SettingsRow(a.accountLabel + " · " + a.accountType.name.lowercase(),
                                Money.format(row.availablePaise),
                                subtitle = a.accountLast4?.let { "Bank ••$it" })
                        }
                        if (ledger.unassignedCount > 0) {
                            SettingsRow("Unassigned · ${ledger.unassignedCount} need review",
                                Money.format(ledger.unassignedPaise),
                                subtitle = "In total but not assigned to a bank; tap a payment to fix it")
                        }
                        SettingsRow("↻  Update balance", onClick = onUpdateBalance, chevron = true)
                    }
                }
            }
        }

        // ── You ──
        item {
            SettingsSection("You") {
                SettingsCard {
                    SettingsRow("Name", profile?.displayName ?: "—", first = true, chevron = true, onClick = onEditProfile)
                    // The IDs themselves, not a count — "2 linked" hid two wrong IDs for months. They
                    // decide what counts as a transfer to yourself, so a wrong one must show at a glance.
                    val upiIds = profile?.ownVpasCsv.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    SettingsRow(
                        "UPI IDs", subtitle = upiIdsSummary(upiIds) ?: "None added yet",
                        onClick = onEditProfile, chevron = true,
                    )
                    SettingsRow("Linked payment instruments", "${instruments.size}",
                        subtitle = "UPI IDs linked to bank accounts (no extra balance)",
                        onClick = onLinkUpi, chevron = true)
                }
            }
        }

        // ── Data ──
        item {
            SettingsSection("Data") {
                SettingsCard {
                    // Same rows All transactions shows (removed payments left out), so the counts agree.
                    SettingsRow(
                        "Export to CSV", subtitle = "${CsvExport.exportable(txns).size} transactions", first = true,
                        chevron = true,
                        onClick = {
                            CsvExport.share(
                                ctx, txns,
                                ownVpas = profile?.ownVpaSet() ?: emptySet(),
                                ownNames = profile?.ownNameSet() ?: emptySet(),
                            )
                        },
                    )
                    SettingsRow(
                        "Encrypted backup", subtitle = "Password-protected · choose where to save",
                        chevron = true, onClick = { showEncryptedDialog = true },
                    )
                    SettingsRow(
                        "Restore a backup", subtitle = "Encrypted or legacy JSON · merges without duplicates",
                        chevron = true, onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                    )
                    ToggleRow(
                        "Auto-save plaintext to Downloads",
                        if (autoPlaintext) "On — readable by other apps with file access" else "Off (recommended)",
                        on = autoPlaintext,
                        onToggle = { enable ->
                            if (enable) plaintextAction = "auto" else {
                                autoPlaintext = false
                                Backup.setAutoBackupEnabled(ctx, false)
                            }
                        },
                    )
                    val bs = backupStatus
                    SettingsRow(
                        "Legacy unencrypted backup",
                        subtitle = if (bs?.failing == true) "Last attempt failed ${DateTime.ago(bs.lastFailureAt)}"
                            else if (bs != null && bs.lastSuccessAt > 0L)
                                "Last saved ${DateTime.ago(bs.lastSuccessAt)} · ${bs.fileName.orEmpty()}"
                            else "One-time plaintext copy to Downloads",
                        subtitleColor = if (bs?.failing == true) RedDebit else null,
                        chevron = true, onClick = { plaintextAction = "once" },
                    )
                    SettingsRow(
                        "Removed payments",
                        subtitle = if (removed.isEmpty()) "Nothing removed"
                        else "${removed.size} hidden from your totals" +
                            (if (confirmedRemoved > 0) " · $confirmedRemoved your bank confirmed" else ""),
                        chevron = true,
                        onClick = onOpenRemoved,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Uninstalling deletes the private database. Save an encrypted backup first and " +
                        "remember its password; restore it from Welcome after reinstalling. Automatic " +
                        "plaintext backups are off by default. Existing unencrypted Downloads files are " +
                        "not deleted on upgrade — remove them yourself if no longer needed. A chosen cloud " +
                        "provider may sync an exported file even though this app cannot use the Internet.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        // ── Budgets (Phase 2) ──
        item {
            val setCount = budgets.count { it.limitPaise > 0L } + categoryBudgets.count { it.limitPaise > 0L }
            SettingsSection("Budgets") {
                SettingsCard {
                    SettingsRow(
                        "Budgets",
                        value = if (setCount > 0) "$setCount set" else null,
                        subtitle = "Daily, weekly, monthly and category envelopes",
                        first = true, chevron = true, onClick = onOpenBudgets,
                    )
                }
            }
        }

        // ── Spending map (opt-in location, Slice C) ──
        item {
            val locOn = ServiceLocator.locationSettings.enabled && Permissions.isLocationGranted(ctx)
            SettingsSection("Spending map") {
                SettingsCard {
                    SettingsRow(
                        "Location pins",
                        value = if (locOn) "On" else "Off",
                        subtitle = "Map where you were when you paid",
                        first = true, chevron = true, onClick = onOpenLocation,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Optional, off by default. Reads your location only at pay-time, rounds it to your " +
                        "neighbourhood (~110 m), keeps it on this phone — never uploaded.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }

        // ── About — kept LAST so it sits at the very bottom of Settings ──
        item {
            SettingsSection("About") {
                SettingsCard { SettingsRow("Version", versionName, first = true) }
                Spacer(Modifier.height(12.dp))
                Text(
                    // Fully offline — the app has no INTERNET permission, so this claim is enforced, not aspirational.
                    "No Internet permission; the app never connects to a bank or uploads your data. " +
                        "Files you explicitly export or share can leave your device.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reads text only — never screenshots.",
                    style = MaterialTheme.typography.bodySmall, color = TextTertiary,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

// ──────────────────────────────── helpers ────────────────────────────────

/** Section title (titleLarge) hugging its card — grouped in one item so spacedBy doesn't split them. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Glass card over the aurora — blur defaults true (no nested surfaces here). */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassSurface(WalletShapes.large)) { content() }
}

/**
 * Label/value row modeled on DetailRow: hairline divider on top when [!first], label left, optional
 * [value] right under weight, optional [subtitle] under the label, optional trailing [chevron].
 * Whole row is clickable when [onClick] is non-null.
 */
@Composable
private fun SettingsRow(
    label: String,
    value: String? = null,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    first: Boolean = false,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
    val base = Modifier.fillMaxWidth()
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        clickable.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor ?: TextTertiary)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge, color = TextPrimary,
                textAlign = TextAlign.End,
            )
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            // chevron-down rotated to point right
            IconChevronDown(TextTertiary, size = 18.dp, modifier = Modifier.rotate(-90f))
        }
    }
}

/** A row with a label/subtitle on the left and a [TogglePill] on the right (wallet mode, etc.). */
@Composable
private fun ToggleRow(
    label: String,
    subtitle: String,
    on: Boolean,
    first: Boolean = false,
    onToggle: (Boolean) -> Unit,
) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(Modifier.width(16.dp))
        TogglePill(on = on, onToggle = onToggle)
    }
}

/** Capture-health row: StatusScreen.HealthRow made clickable to its fix deep-link. */
@Composable
private fun HealthRow(
    label: String,
    ok: Boolean,
    first: Boolean = false,
    subtitle: String? = null,
    subtitleWarn: Boolean = false,
    onClick: () -> Unit,
) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(HairlineColor))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = if (subtitleWarn) WarnColor.copy(alpha = 0.9f) else TextSecondary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (ok) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCheck(GreenCredit, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text("Healthy", style = MaterialTheme.typography.labelLarge, color = GreenCredit)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(PillShape).background(RedDebit))
                Spacer(Modifier.width(7.dp))
                Text("Off", style = MaterialTheme.typography.labelLarge, color = RedDebit)
            }
        }
    }
}
