package com.pocketide.mobile.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.pocketide.mobile.AppIdentity
import com.pocketide.mobile.ui.components.MilestoneBadge
import com.pocketide.mobile.ui.theme.PocketMonoFont

/** Settings: real info/actions only; future capabilities shown disabled with badges. */
@Composable
fun SettingsScreen(
    workspacePath: String,
    onMessage: (String) -> Unit
) {
    SettingsContent(workspacePath = workspacePath, onMessage = onMessage)
}

@Composable
fun SettingsContent(
    workspacePath: String,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val repoUrl = "https://github.com/ygunasekhar72-ops/pocketide"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingGroup("App") {
            SettingRow("Name", AppIdentity.APP_NAME)
            SettingRow("Application ID", AppIdentity.APPLICATION_ID)
            SettingRow("Version", AppIdentity.VERSION)
        }

        SettingGroup("Workspace") {
            ActionRow(
                key = "Location",
                value = workspacePath,
                actionLabel = "Copy",
                onAction = {
                    clipboard.setText(AnnotatedString(workspacePath))
                    onMessage("Workspace path copied")
                }
            )
        }

        SettingGroup("Editor") {
            SettingRow("Theme", "Dark (locked; light theme decision pending)")
            ActionRow(
                key = "Font size",
                value = "Default",
                actionLabel = "Adjust",
                enabled = false,
                onAction = {}
            )
            LockedRow("Editor engine unlock (Monaco)", "M2")
        }

        SettingGroup("Terminal") {
            LockedRow("Shell runtime: Android mksh", "M1")
            LockedRow("Optional runtimes: Python/Node, first-run download", "M7")
        }

        SettingGroup("AI") {
            LockedRow("Provider / key (Keystore-encrypted)", "M6")
            Text(
                text = "Keys never enter WebView JavaScript, logs, or version control.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingGroup("About") {
            ActionRow(
                key = "Repository",
                value = "github.com/ygunasekhar72-ops/pocketide",
                actionLabel = "Open",
                onAction = {
                    onMessage("Opening repository…")
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl))
                        )
                    }.onFailure { onMessage("No browser available") }
                }
            )
            SettingRow("License", "Apache-2.0")
        }
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(content = content)
    }
}

@Composable
private fun SettingRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            fontFamily = PocketMonoFont,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionRow(
    key: String,
    value: String,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(key, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable(enabled = enabled, onClick = onAction)
        ) {
            Text(
                text = actionLabel,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LockedRow(label: String, badge: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MilestoneBadge(badge)
    }
}
