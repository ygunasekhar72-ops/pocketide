package com.pocketide.mobile.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.pocketide.mobile.ui.components.EmptyState
import com.pocketide.mobile.ui.components.MilestoneBadge
import com.pocketide.mobile.ui.theme.PocketMonoFont

/**
 * Interactive terminal (M2): the real [PtySession] engine (own JNI PTY +
 * Android mksh) rendered as a bounded text stream with live input, session
 * start/stop and pty resize propagation. No engine simulation happens here;
 * a vt100-style ANSI renderer is a later-phase upgrade (see
 * [TerminalViewModel] notes and the M2 device criteria in the report).
 */
@Composable
fun TerminalScreen(
    projectName: String?,
    viewModel: TerminalViewModel
) {
    val ui by viewModel.uiState.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        SessionHeader(projectName = projectName, ui = ui)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutputArea(
            ui = ui,
            onResize = { rows, cols -> viewModel.resize(rows, cols) },
            modifier = Modifier.weight(1f)
        )
        CommandInputBar(
            enabled = ui.live,
            onSend = viewModel::sendCommand
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SessionControlBar(
            ui = ui,
            onStart = { viewModel.start(projectName) },
            onStop = viewModel::stop,
            onClear = viewModel::clear
        )
    }
}

@Composable
private fun SessionHeader(projectName: String?, ui: TerminalUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (ui.live) "mksh session" else "session",
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = ui.cwdLabel.ifEmpty { projectName ?: "workspace" },
                fontFamily = PocketMonoFont,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MilestoneBadge(ui.statusLabel)
    }
}

/**
 * Bounded text render viewport. Emits pty resize propagation derived from
 * the actual layout box (mono font metrics approximated; the native side
 * validates bounds). ANSI escape sequences render verbatim at this PoC.
 */
@Composable
private fun OutputArea(
    ui: TerminalUiState,
    onResize: (rows: Int, cols: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        if (ui.text.isEmpty() && !ui.live) {
            EmptyState(
                title = "No terminal session",
                description = "Start with ▶ run a real mksh session in the PTY. Nothing is simulated.",
                symbol = ">_",
                badge = { MilestoneBadge("engine · native PTY") }
            )
        } else {
            val scroll = rememberScrollState()
            LaunchedEffect(ui.text.length) {
                scroll.animateScrollTo(scroll.maxValue)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
            ) {
                Text(
                    text = ui.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = PocketMonoFont,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        val density = LocalDensity.current
        LaunchedEffect(
            maxHeight, maxWidth, ui.live
        ) {
            if (!ui.live) return@LaunchedEffect
            val rowsCols = with(density) {
                val rowPx = 16.dp.toPx()
                val colPx = 7.3.dp.toPx()
                (maxHeight.toPx() / rowPx).toInt().coerceIn(1, 2048) to
                    (maxWidth.toPx() / colPx).toInt().coerceIn(1, 2048)
            }
            onResize(rowsCols.first, rowsCols.second)
        }
    }
}

@Composable
private fun CommandInputBar(
    enabled: Boolean,
    onSend: (String) -> Unit
) {
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
            label = { Text(if (enabled) "Command" else "Command input (start a session)") },
            placeholder = {
                Text("$ ", fontFamily = PocketMonoFont, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        )
        IconButton(
            onClick = {
                onSend(draft)
                draft = ""
            },
            enabled = enabled && draft.isNotEmpty()
        ) {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "Send command"
            )
        }
    }
}

@Composable
private fun SessionControlBar(
    ui: TerminalUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalIconButton(onClick = onStart, enabled = !ui.live, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "New session")
        }
        FilledTonalIconButton(onClick = onStop, enabled = ui.live, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Stop session")
        }
        FilledTonalIconButton(onClick = onClear, enabled = ui.text.isNotEmpty(), modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Clear output")
        }
        Text(
            text = if (ui.live) "live mksh in the native PTY" else "press ▶ to start a session",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
