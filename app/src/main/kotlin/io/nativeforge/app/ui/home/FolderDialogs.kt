package io.nativeforge.app.ui.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nativeforge.core.project.ProjectNameValidator
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.NfDialog

/** What a folder chosen in the system picker is used for. */
enum class FolderPurpose { OPEN_PROJECT, NEW_PROJECT_LOCATION }

/**
 * Asked before the first folder pick: building in place needs Android's "Files and media"
 * permission. Opening a project can fall back to importing a copy without it.
 */
@Composable
fun StorageAccessDialog(purpose: FolderPurpose, onAllow: () -> Unit, onCopyInstead: () -> Unit, onDismiss: () -> Unit) {
    NfDialog(
        title = if (purpose == FolderPurpose.OPEN_PROJECT) "Open a folder from your phone" else "Create the project in a folder",
        onDismiss = onDismiss,
        maxWidth = 480.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            if (purpose == FolderPurpose.OPEN_PROJECT) DialogSecondaryButton("Import a copy", onCopyInstead)
            DialogPrimaryButton("Allow access", onAllow)
        },
    ) {
        Text(
            "To edit and build a project where it is (for example in Documents, Download or on an SD card), " +
                "NativeForge needs Android's permission to access files. The compiler, CMake, Make and git work " +
                "with ordinary folders, so the files stay where they are and other apps keep seeing your changes. " +
                "Build outputs go to NativeForge's private storage.\n\n" +
                (if (purpose == FolderPurpose.OPEN_PROJECT) "Without the permission you can still import a copy of a folder." else "") +
                "NativeForge only reads the folders you open.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/**
 * A picked folder that cannot be built in place: it belongs to another app (a cloud drive)
 * or NativeForge has no file access. Offers to copy it into the workspace.
 */
@Composable
fun ImportCopyDialog(
    folderName: String,
    onPhoneStorage: Boolean,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    NfDialog(
        title = "Import a copy of $folderName?",
        onDismiss = onDismiss,
        maxWidth = 480.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            if (onPhoneStorage) DialogSecondaryButton("Allow access", onOpenSettings)
            DialogPrimaryButton("Import a copy", onImport)
        },
    ) {
        Text(
            if (onPhoneStorage) {
                "NativeForge has no permission to access files, so it cannot build this folder where it is. " +
                    "Allow access in Android's settings (Permissions › Files and media) and open it again, " +
                    "or import a copy into NativeForge's own storage. Changes to a copy do not reach the original folder."
            } else {
                "This folder is not on the phone's storage (it may be a cloud drive or another app's files), so it " +
                    "cannot be built where it is. NativeForge can copy it into its own storage as a new project. " +
                    "Changes to the copy do not reach the original folder."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** A valid, unused project name for a copied folder called [folderName]. */
internal fun projectNameForCopy(folderName: String, existingNames: Set<String>): String {
    val cleaned = folderName.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim(' ', '.', '-', '_').take(56)
        .ifEmpty { "Imported" }
        .let { if (it.first().isLetterOrDigit()) it else "Project $it" }
    return generateSequence(1) { it + 1 }
        .map { if (it == 1) cleaned else "$cleaned $it" }
        .first { it !in existingNames && ProjectNameValidator.validate(it) == null }
}
