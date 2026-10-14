package com.goushik.upiwallet.ui.common

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.util.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared restore path for Settings and first-run setup. Supports legacy plaintext and password-protected
 * files; a wrong password never imports a partial ledger and lets the user try again. */
@Composable
fun rememberBackupRestorePicker(
    holdOnboarding: Boolean = false,
    onRestored: (Backup.RestoreResult) -> Unit = {},
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val ctx = LocalContext.current
    val latestOnRestored by rememberUpdatedState(onRestored)
    var pending by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun restore(uri: Uri, secret: CharArray? = null) {
        busy = true
        ServiceLocator.appScope.launch {
            val result = try {
                runCatching { Backup.restoreFromUri(ctx, ServiceLocator.db, uri, holdOnboarding, secret) }
            } finally { secret?.fill('\u0000') }
            withContext(Dispatchers.Main) {
                busy = false
                Toast.makeText(ctx, result.fold(
                    onSuccess = { Backup.restoreSummary(it) },
                    onFailure = { it.message ?: "Couldn't restore that file" },
                ), Toast.LENGTH_LONG).show()
                if (result.isSuccess) {
                    pending = null
                    latestOnRestored(result.getOrThrow())
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ServiceLocator.appScope.launch {
                val encrypted = runCatching { Backup.isEncryptedUri(ctx, uri) }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    if (encrypted) pending = uri else restore(uri)
                }
            }
        }
    }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) { pending = null; password = "" } },
            title = { Text("Restore encrypted backup") },
            text = {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Backup password") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !busy, onClick = {
                    val secret = password.toCharArray()
                    password = ""
                    pending?.let { restore(it, secret) }
                }) { Text(if (busy) "Restoring…" else "Restore") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pending = null; password = "" }) { Text("Cancel") }
            },
        )
    }
    return launcher
}
