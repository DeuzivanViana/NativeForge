package io.nativeforge.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nativeforge.app.signing.KeystorePasswords
import io.nativeforge.app.signing.SavedKeystore
import io.nativeforge.app.signing.SigningKeyRepository
import io.nativeforge.core.export.signing.CertificateGenerator
import io.nativeforge.core.export.signing.DistinguishedName
import io.nativeforge.core.ui.component.DenseRow
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.SwitchRow
import kotlinx.coroutines.launch

/** "New signing key": the fields of Android Studio's "New Key Store" dialog. */
@Composable
fun CreateKeyDialog(viewModel: ExportViewModel, defaultName: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("$defaultName upload key") }
    var alias by remember { mutableStateOf("upload") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var years by remember { mutableStateOf(CertificateGenerator.DEFAULT_VALIDITY_YEARS.toString()) }
    var remember by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val shortPassword = password.isNotEmpty() && password.length < SigningKeyRepository.MIN_PASSWORD
    val mismatch = confirm.isNotEmpty() && confirm != password
    val validYears = years.toIntOrNull()?.takeIf { it in 1..100 }
    val ready = !busy && name.isNotBlank() && alias.isNotBlank() && password.length >= SigningKeyRepository.MIN_PASSWORD &&
        confirm == password && owner.isNotBlank() && validYears != null && (country.isEmpty() || country.length == 2)

    NfDialog(
        title = "New signing key",
        subtitle = "A PKCS#12 keystore with one RSA 2048 key",
        onDismiss = onDismiss,
        maxWidth = 480.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton(if (busy) "Creating…" else "Create", enabled = ready, onClick = {
                busy = true
                scope.launch {
                    error = viewModel.createKey(
                        name, alias.trim(), password.toCharArray(),
                        DistinguishedName(commonName = owner, organization = organization, country = country),
                        validYears ?: CertificateGenerator.DEFAULT_VALIDITY_YEARS, remember,
                    )
                    busy = false
                    if (error == null) onDismiss()
                }
            })
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NfTextField(name, { name = it }, label = "Name in NativeForge")
            NfTextField(alias, { alias = it.trim() }, label = "Key alias", monospace = true)
            NfTextField(
                password, { password = it }, label = "Password", password = true,
                error = "At least ${SigningKeyRepository.MIN_PASSWORD} characters".takeIf { shortPassword },
                supportingText = "Used for the keystore and the key, as Android Studio does.",
            )
            NfTextField(confirm, { confirm = it }, label = "Confirm password", password = true, error = "Passwords differ".takeIf { mismatch })
            NfTextField(owner, { owner = it }, label = "Your name or company", supportingText = "Stored in the certificate (CN).")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NfTextField(organization, { organization = it }, label = "Organization (optional)", modifier = Modifier.weight(1f))
                NfTextField(country, { country = it.take(2).uppercase() }, label = "Country", placeholder = "BR", modifier = Modifier.weight(0.5f))
            }
            NfTextField(
                years, { years = it.filter(Char::isDigit).take(3) }, label = "Valid for (years)", keyboardType = KeyboardType.Number,
                supportingText = "Google Play needs at least 25 years.",
            )
            SwitchRow(
                title = "Remember the password",
                subtitle = "Encrypted with this device's keystore",
                checked = remember,
                onCheckedChange = { remember = it },
            )
            Text(
                "Back up the key after creating it. If you lose the file or its password, you cannot publish updates to apps signed with it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Import in two steps: the keystore password unlocks the file, then a key is chosen. */
@Composable
fun ImportKeyDialog(viewModel: ExportViewModel, pending: PendingImport, onDismiss: () -> Unit) {
    var storePassword by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var separateKeyPassword by remember { mutableStateOf(false) }
    var alias by remember(pending.aliases) { mutableStateOf(pending.aliases?.firstOrNull()?.alias.orEmpty()) }
    var name by remember { mutableStateOf(pending.fileName.substringBeforeLast('.').ifBlank { "Imported key" }) }
    var remember by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val unlocked = pending.aliases != null

    NfDialog(
        title = "Import signing key",
        subtitle = pending.fileName,
        onDismiss = onDismiss,
        maxWidth = 480.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            if (!unlocked) {
                DialogPrimaryButton("Open", enabled = !busy && storePassword.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch { error = viewModel.unlockImport(storePassword.toCharArray()); busy = false }
                })
            } else {
                DialogPrimaryButton("Import", enabled = !busy && alias.isNotEmpty() && name.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        val passwords = KeystorePasswords(storePassword.toCharArray(), keyPassword.takeIf { separateKeyPassword }?.toCharArray())
                        error = viewModel.finishImport(name, alias, passwords, remember)
                        busy = false
                    }
                })
            }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NfTextField(storePassword, { storePassword = it }, label = "Keystore password", password = true)
            if (unlocked) {
                Text("Key", style = MaterialTheme.typography.labelMedium)
                Column {
                    pending.aliases.orEmpty().forEach { entry ->
                        DenseRow(
                            title = entry.alias,
                            subtitle = entry.certificate?.let { "${it.subjectX500Principal.name}\nSHA-256 ${shortFingerprint(entry.fingerprint)}" },
                            selected = entry.alias == alias,
                            onClick = { alias = entry.alias },
                        )
                    }
                }
                SwitchRow(
                    title = "The key has its own password",
                    subtitle = "Off: same as the keystore password",
                    checked = separateKeyPassword,
                    onCheckedChange = { separateKeyPassword = it },
                )
                if (separateKeyPassword) NfTextField(keyPassword, { keyPassword = it }, label = "Key password", password = true)
                NfTextField(name, { name = it }, label = "Name in NativeForge")
                SwitchRow(
                    title = "Remember the passwords",
                    subtitle = "Encrypted with this device's keystore",
                    checked = remember,
                    onCheckedChange = { remember = it },
                )
            } else {
                Text(
                    "JKS, PKCS#12 (.p12, .pfx) and BKS files from keytool or Android Studio work. The file is copied into NativeForge's private storage.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Asked at export time when the key's passwords are not saved (or no longer work). */
@Composable
fun KeyPasswordDialog(keystore: SavedKeystore, error: String?, onExport: (KeystorePasswords, Boolean) -> Unit, onDismiss: () -> Unit) {
    var storePassword by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var separate by remember { mutableStateOf(false) }
    var remember by remember { mutableStateOf(false) }
    NfDialog(
        title = "Unlock '${keystore.name}'",
        subtitle = "Alias ${keystore.alias}",
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton("Export", enabled = storePassword.isNotEmpty(), onClick = {
                onExport(KeystorePasswords(storePassword.toCharArray(), keyPassword.takeIf { separate }?.toCharArray()), remember)
            })
        },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NfTextField(storePassword, { storePassword = it }, label = "Keystore password", password = true)
            SwitchRow(title = "The key has its own password", checked = separate, onCheckedChange = { separate = it })
            if (separate) NfTextField(keyPassword, { keyPassword = it }, label = "Key password", password = true)
            SwitchRow(title = "Remember on this device", checked = remember, onCheckedChange = { remember = it })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** First and last bytes of a fingerprint, enough to tell keys apart. */
fun shortFingerprint(fingerprint: String?): String =
    fingerprint?.split(':')?.let { if (it.size > 8) it.take(4).joinToString(":") + "…" + it.takeLast(4).joinToString(":") else fingerprint } ?: "?"
