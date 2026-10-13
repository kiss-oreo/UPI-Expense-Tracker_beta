package com.goushik.upiwallet.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.data.AccountType
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.domain.BalanceCalculator
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.IconCheck
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.IconInfo
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.GreenCredit
import com.goushik.upiwallet.ui.theme.HairlineColor
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.TextTertiary
import com.goushik.upiwallet.ui.theme.Violet500
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Ids
import com.goushik.upiwallet.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun moneyFilter(s: String): String = s.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }

/** Banks the per-account spend matcher (domain/AccountMatch) can canonicalise — tapping a chip pre-fills
 *  the name so attribution works. Any other bank is still fine to type (the balance is a global sum). */
private val COMMON_BANKS = listOf("HDFC", "SBI", "ICICI", "Axis", "Kotak", "PNB")

/**
 * ADD a bank account after onboarding — the fix for "if you don't add one at signup you never can".
 * Distinct from [UpdateBalanceScreen] (which re-anchors ALL accounts to a fresh truth): this ADDS a
 * single account and stamps it at the existing accounts' shared cutoff ([BalanceCalculator.anchorStampFor]),
 * so `available` rises by exactly the entered balance and the existing accounts' tracking is untouched —
 * it can never re-cut history and inflate the balance. Does NOT change the spend-only/balance mode.
 */
@Composable
fun AddAccountScreen(onBack: () -> Unit) {
    BackHandler { onBack() }

    val profile by ServiceLocator.repository.observeProfile().collectAsStateWithLifecycle(null)
    val showBalance = profile?.showBalance ?: true
    var added by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        TopBar(onBack)
        if (added) AddedConfirmation(showBalance, onBack) else AddForm(onAdded = { added = true })
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
        Text("Add account", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddForm(onAdded: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AccountType.BANK) }
    var last4 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = Money.parsePaise(amount)
    val valid = name.isNotBlank() && parsed != null && (last4.isBlank() ||
        (kind == AccountType.BANK && last4.length == 4 && last4.all(Char::isDigit)))

    Spacer(Modifier.height(12.dp))
    Text("Add an account", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
    Spacer(Modifier.height(10.dp))
    Text(
        "Keep cash, banks, cards and wallets separate. Link a UPI ID to a bank later — a UPI ID " +
            "does not have its own balance. For cards, a negative opening balance means money owed.",
        style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
    )
    Spacer(Modifier.height(24.dp))

    FieldLabel("Type")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountType.entries.forEach { type ->
            BankChip(type.name.lowercase().replaceFirstChar { it.uppercase() }, kind == type) {
                kind = type
                if (type != AccountType.BANK) last4 = ""
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    FieldLabel("Account name")
    if (kind == AccountType.BANK) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            COMMON_BANKS.forEach { bank ->
                BankChip(bank, selected = name.equals(bank, ignoreCase = true), onClick = { name = bank })
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    WalletTextField(
        name, { name = it }, placeholder = if (kind == AccountType.BANK) "e.g. Federal Bank" else "e.g. Cash in hand",
        textStyle = MaterialTheme.typography.titleLarge,
    )
    if (kind == AccountType.BANK) {
        Spacer(Modifier.height(16.dp))
        FieldLabel("Bank account last 4", "optional; helps match bank SMS")
        WalletTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, placeholder = "e.g. 1234",
            keyboardType = KeyboardType.Number)
    }

    Spacer(Modifier.height(18.dp))
    FieldLabel("Current balance")
    WalletTextField(
        amount, { amount = moneyFilter(it) }, prefix = "₹", placeholder = "0",
        keyboardType = KeyboardType.Decimal, textStyle = MaterialTheme.typography.headlineMedium,
    )
    Spacer(Modifier.height(14.dp))
    HintRow("Your opening balance is a snapshot; transfers and payments update it from here.")
    error?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = RedDebit)
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        "Add account",
        onClick = {
            val label = name.trim()
            val type = kind
            val paise = parsed ?: return@PrimaryButton
            val tail = last4.ifBlank { null }
            ServiceLocator.appScope.launch {
                runCatching { ServiceLocator.repository.addAccount(label, type, paise, tail) }
                    .onSuccess { withContext(Dispatchers.Main) { onAdded() } }
                    .onFailure { withContext(Dispatchers.Main) { error = it.message ?: "Couldn't add account" } }
            }
        },
        enabled = valid,
    )
}

@Composable
private fun AddedConfirmation(showBalance: Boolean, onBack: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(80.dp).clip(RoundedCornerShape(999.dp))
                .background(GreenCredit.copy(alpha = 0.12f))
                .border(1.5.dp, GreenCredit.copy(alpha = 0.5f), RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) { IconCheck(GreenCredit, size = 38.dp) }
        Spacer(Modifier.height(20.dp))
        Text("Account added", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        if (!showBalance) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Turn on \"Show account balance\" in Settings to see your wallet.",
                style = MaterialTheme.typography.bodyLarge, color = TextSecondary,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
    Spacer(Modifier.height(28.dp))
    PrimaryButton("Done", onBack)
}

@Composable
private fun BankChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) TextPrimary else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Violet500.copy(alpha = 0.22f) else Color(0x0DFFFFFF))
            .border(
                1.dp,
                if (selected) Violet500.copy(alpha = 0.5f) else HairlineColor,
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun HintRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconInfo(TextTertiary, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}
