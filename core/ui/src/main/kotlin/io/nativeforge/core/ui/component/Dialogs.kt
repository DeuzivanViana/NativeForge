package io.nativeforge.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Padding for compact buttons in dialog footers and forms. */
val CompactButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

/**
 * Flat, editor-style dialog: a header with title and close button, a body, and
 * an optional footer with actions on the right. Replaces Material's
 * AlertDialog, whose large rounded card does not fit the compact UI.
 */
@Composable
fun NfDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxWidth: Dp = 560.dp,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 8.dp,
            modifier = modifier.padding(16.dp).widthIn(max = maxWidth).fillMaxWidth().imePadding(),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ToolbarIconButton(Icons.Filled.Close, "Close", onDismiss)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f, fill = false)) { content() }
                if (actions != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { actions() }
                }
            }
        }
    }
}

/** Primary footer button for [NfDialog] and forms (32dp high). */
@Composable
fun DialogPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false) {
    val colors = if (destructive) {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = MaterialTheme.shapes.small,
        contentPadding = CompactButtonPadding,
        modifier = Modifier.height(32.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Secondary footer button for [NfDialog]. */
@Composable
fun DialogSecondaryButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        contentPadding = CompactButtonPadding,
        modifier = Modifier.height(32.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Yes/no question in the compact dialog style. */
@Composable
fun NfConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    NfDialog(
        title = title,
        onDismiss = onDismiss,
        maxWidth = 420.dp,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton(confirmLabel, onConfirm, destructive = destructive)
        },
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
        Spacer(Modifier.height(2.dp))
    }
}
