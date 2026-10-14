package com.goushik.upiwallet.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.goushik.upiwallet.data.AccountType
import com.goushik.upiwallet.data.PaymentInstrumentEntity
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.ui.common.FieldLabel
import com.goushik.upiwallet.ui.common.IconChevronLeft
import com.goushik.upiwallet.ui.common.PrimaryButton
import com.goushik.upiwallet.ui.common.WalletTextField
import com.goushik.upiwallet.ui.theme.RedDebit
import com.goushik.upiwallet.ui.theme.TextPrimary
import com.goushik.upiwallet.ui.theme.TextSecondary
import com.goushik.upiwallet.ui.theme.WalletShapes
import com.goushik.upiwallet.ui.theme.glassSurface
import com.goushik.upiwallet.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UPI IDs are aliases of existing banks, never additional accounts with their own balances. */
@Composable
fun LinkUpiScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val repo = ServiceLocator.repository
    val accounts by repo.observeAnchors().collectAsStateWithLifecycle(emptyList())
    val instruments by repo.observeInstruments().collectAsStateWithLifecycle(emptyList())
    var label by remember { mutableStateOf("") }
    var vpa by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val banks = accounts.filter { it.accountType == AccountType.BANK }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).glassSurface(WalletShapes.medium, blur = false)
                .clickable(onClick = onBack), contentAlignment = Alignment.Center) { IconChevronLeft(TextPrimary) }
            Text("Linked UPI IDs", style = MaterialTheme.typography.titleLarge, color = TextPrimary,
                modifier = Modifier.padding(start = 12.dp))
        }
        Text("These are payment shortcuts, not separate money. Linking an ID to a bank never " +
            "adds its balance again. Bank SMS can match by bank and last four; an unreadable payment " +
            "stays unassigned until you choose an account in its detail.",
            color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        FieldLabel("Name")
        WalletTextField(label, { label = it }, placeholder = "e.g. My GPay")
        Spacer(Modifier.height(14.dp))
        FieldLabel("Your UPI ID")
        WalletTextField(vpa, { vpa = it }, placeholder = "name@bank")
        Spacer(Modifier.height(14.dp))
        FieldLabel("Funded by")
        banks.forEach { a ->
            Text((if (accountId == a.id) "✓  " else "○  ") + a.accountLabel +
                (a.accountLast4?.let { " ••$it" } ?: ""),
                modifier = Modifier.fillMaxWidth().clickable { accountId = a.id }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
        }
        if (banks.isEmpty()) Text("Add a bank account first.", color = TextSecondary)
        error?.let { Text(it, color = RedDebit, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            "Link UPI ID",
            onClick = {
                val chosen = accountId ?: return@PrimaryButton
                val item = PaymentInstrumentEntity(Ids.uuid7(), label.trim(), vpa.trim(), chosen)
                ServiceLocator.appScope.launch {
                    runCatching { repo.upsertInstrument(item) }
                        .onSuccess { withContext(Dispatchers.Main) { label = ""; vpa = ""; error = null } }
                        .onFailure { withContext(Dispatchers.Main) { error = it.message ?: "Couldn't link ID" } }
                }
            },
            enabled = label.isNotBlank() && vpa.contains('@') && accountId != null,
        )
        Spacer(Modifier.height(24.dp))
        instruments.forEach { item ->
            val bank = banks.firstOrNull { it.id == item.accountId }?.accountLabel ?: "Account removed"
            Row(Modifier.fillMaxWidth().glassSurface(WalletShapes.medium, blur = false).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text("${item.vpa} · $bank", color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Unlink", color = RedDebit, modifier = Modifier.clickable {
                    ServiceLocator.appScope.launch { repo.deleteInstrument(item.id) }
                }.padding(8.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(28.dp))
    }
}
