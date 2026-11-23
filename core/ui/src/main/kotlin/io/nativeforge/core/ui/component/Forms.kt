package io.nativeforge.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Small caption above a form field or a group of options. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

/**
 * Compact single-line text field (36dp), with an optional label above and a
 * hint or error below. Denser than Material's OutlinedTextField.
 */
@Composable
fun NfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    error: String? = null,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Hides the text (access tokens). */
    password: Boolean = false,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    /** Lets dialogs focus the field when they open. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.outlineVariant
    }
    val textStyle = MaterialTheme.typography.bodyMedium.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it }
    Column(modifier) {
        if (label != null) FieldLabel(label)
        Box(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceContainerHigh)
                .border(1.dp, borderColor, MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, style = textStyle, color = colors.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    autoCorrectEnabled = !password && !monospace,
                    imeAction = imeAction,
                ),
                keyboardActions = keyboardActions,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
                    .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        val below = error ?: supportingText
        if (below != null) {
            Text(
                below,
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/**
 * Row of mutually exclusive options drawn as a compact segmented control.
 * Disabled options stay visible so the user can see what exists.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.height(32.dp),
    ) {
        Row(Modifier.padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                val isEnabled = enabled(option)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 26.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(if (isSelected) colors.primary.copy(alpha = 0.2f) else colors.surfaceContainerHigh)
                        .then(if (isEnabled) Modifier.clickable { onSelect(option) } else Modifier)
                        .alpha(if (isEnabled) 1f else 0.38f)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) colors.primary else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Title, optional description and a switch; the whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .heightIn(min = 44.dp)
            .padding(vertical = 2.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Bordered group of form content with a small heading, like a settings card. */
@Composable
fun FormSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}
