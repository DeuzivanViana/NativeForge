package io.nativeforge.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.nativeforge.core.project.ProjectNameValidator
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.FormSection
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.SwitchRow
import io.nativeforge.core.vcs.GitCredentials
import io.nativeforge.core.vcs.GitUrls

/**
 * "Clone repository": downloads a project from an HTTPS git address into the workspace.
 * CMake, Makefile and Ninja projects are recognised and keep their own build files.
 */
@Composable
fun CloneDialog(
    existingNames: Set<String>,
    state: CloneState?,
    onClone: (url: String, name: String, branch: String, credentials: GitCredentials?) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var branch by rememberSaveable { mutableStateOf("") }
    var isPrivate by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    // The token is not saved with the screen state.
    var token by androidx.compose.runtime.remember { mutableStateOf("") }

    val running = state != null && state.error == null
    val urlError = url.takeIf { it.isNotBlank() }?.let(GitUrls::validate)
    val effectiveName = (if (nameEdited) name else url.takeIf { it.isNotBlank() }?.let(GitUrls::projectName).orEmpty()).trim()
    val nameError = when {
        effectiveName.isEmpty() -> null
        effectiveName in existingNames -> "A project with this name already exists"
        else -> ProjectNameValidator.validate(effectiveName)
    }
    val canClone = !running && url.isNotBlank() && urlError == null && effectiveName.isNotEmpty() && nameError == null &&
        (!isPrivate || (user.isNotBlank() && token.isNotBlank()))

    NfDialog(
        title = "Clone repository",
        subtitle = "Download a project from GitHub, GitLab, Codeberg…",
        onDismiss = { if (running) onCancel() else onDismiss() },
        maxWidth = 560.dp,
        actions = {
            if (running) {
                DialogSecondaryButton("Cancel", onCancel)
            } else {
                DialogSecondaryButton("Close", onDismiss)
                DialogPrimaryButton("Clone", {
                    onClone(url.trim(), effectiveName, branch.trim(), if (isPrivate) GitCredentials(user.trim(), token.trim()) else null)
                }, enabled = canClone)
            }
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NfTextField(
                value = url,
                onValueChange = { url = it },
                label = "Repository address",
                placeholder = "https://github.com/user/game.git",
                error = urlError,
                monospace = true,
                keyboardType = KeyboardType.Uri,
            )
            NfTextField(
                value = if (nameEdited) name else effectiveName,
                onValueChange = { name = it; nameEdited = true },
                label = "Project name",
                error = nameError,
            )
            NfTextField(branch, { branch = it }, label = "Branch or tag", placeholder = "default branch", monospace = true)
            FormSection("Private repository") {
                SwitchRow("Sign in", checked = isPrivate, onCheckedChange = { isPrivate = it }, subtitle = "User name and access token, used only for this download")
                if (isPrivate) {
                    NfTextField(user, { user = it }, label = "User name")
                    NfTextField(token, { token = it }, label = "Access token", password = true)
                }
            }
            Text(
                "The build system (CMake, Makefile or Ninja), language and libraries are detected and can be changed " +
                    "in Build settings. Submodules are not downloaded and nothing in the repository runs until you build it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                state?.error != null -> Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                state != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.message + (state.percent?.let { " · $it%" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    if (state.percent != null) {
                        LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
