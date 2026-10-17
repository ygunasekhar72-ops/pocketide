package com.pocketide.mobile.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.mobile.ui.components.EmptyState
import com.pocketide.mobile.ui.components.MilestoneBadge
import com.pocketide.mobile.ui.theme.PocketMonoFont

/**
 * Terminal surface foundation (session header, output area, input row,
 * session controls). No PTY, no execution, no simulated output — the engine
 * connects in M1 and these hooks receive the real session state then.
 */
@Composable
fun TerminalScreen(projectName: String?) {
    TerminalContent(projectName = projectName)
}

@Composable
fun TerminalContent(projectName: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionHeader(projectName = projectName)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            EmptyState(
                title = "No terminal session",
                description = "The terminal engine (real PTY shell) connects in M1. Nothing is simulated here.",
                symbol = ">_",
                badge = { MilestoneBadge("engine · M1") }
            )
        }
        CommandInputBar(enabled = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SessionControlBar()
    }
}

@Composable
private fun SessionHeader(projectName: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "session 0",
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = projectName ?: "workspace",
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MilestoneBadge("not connected")
    }
}

@Composable
private fun CommandInputBar(enabled: Boolean) {
    var draft by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            label = { Text(if (enabled) "Command" else "Command input · connects in M1") },
            placeholder = {
                Text("$ ", fontFamily = PocketMonoFont, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        )
        IconButton(onClick = {}, enabled = enabled) {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "Send command"
            )
        }
    }
}

/** Clear/stop are real controls wired to an engine that does not exist yet — disabled on purpose. */
@Composable
private fun SessionControlBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalIconButton(onClick = {}, enabled = false, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "New session")
        }
        FilledTonalIconButton(onClick = {}, enabled = false, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Stop session")
        }
        Text(
            text = "New session · Stop · Clear activate with the M1 terminal engine",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
