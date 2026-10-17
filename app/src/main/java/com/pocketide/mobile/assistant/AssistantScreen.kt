package com.pocketide.mobile.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketide.mobile.ui.components.EmptyState
import com.pocketide.mobile.ui.components.MilestoneBadge
import com.pocketide.mobile.ui.theme.PocketMonoFont

/**
 * AI assistant panel foundation. No provider, no streaming, no fabricated
 * responses. Real hooks: navigation to Settings for provider configuration,
 * and the approval-state summary that M6's tool loop must honor.
 */
@Composable
fun AssistantScreen(
    projectName: String?,
    onNavigateToSettings: () -> Unit
) {
    AssistantContent(projectName = projectName, onNavigateToSettings = onNavigateToSettings)
}

@Composable
fun AssistantContent(
    projectName: String?,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        ProviderStatusRow(onNavigateToSettings = onNavigateToSettings)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            EmptyState(
                title = if (projectName == null) "Assistant not connected"
                else "Assistant not connected · $projectName context",
                description = "Once a provider is configured, suggestions and plans appear here. File edits always require your approval first — nothing changes silently.",
                symbol = "AI",
                badge = { MilestoneBadge("provider · M6") }
            )
        }
        ApprovalRulesRow()
        PromptBar(enabled = true, sendEnabled = false)
    }
}

@Composable
private fun ProviderStatusRow(onNavigateToSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Provider: not configured",
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = "Any OpenAI-compatible endpoint · key stored in Android Keystore",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onNavigateToSettings) { Text("Configure") }
    }
}

/** Visible summary of the approval boundary that M6 implements for real. */
@Composable
private fun ApprovalRulesRow() {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = "Approval rules (enforced from M6):",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = PocketMonoFont
        )
        Text(
            text = "• File changes → shown as diffs, accept per file\n• Terminal commands → confirm every time\n• Config: read-only context",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PromptBar(sendEnabled: Boolean) {
    var prompt by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.weight(1f),
            enabled = true,
            singleLine = true,
            label = { Text("Ask (send unlocks in M6)") }
        )
        IconButton(onClick = {}, enabled = sendEnabled) {
            Icon(imageVector = Icons.Filled.Send, contentDescription = "Send prompt")
        }
    }
}
