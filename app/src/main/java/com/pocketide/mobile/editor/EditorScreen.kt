package com.pocketide.mobile.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.mobile.ui.components.EmptyState
import com.pocketide.mobile.ui.components.MilestoneBadge
import com.pocketide.mobile.ui.components.MonoIcon
import com.pocketide.mobile.ui.theme.PocketMonoFont

/**
 * Editor foundation (files/breadcrumbs/tabs/accessory bar).
 * Deliberately engineless: Monaco integration is M2; file access is M3.
 * No file content is displayed or simulated in this milestone.
 */
@Composable
fun EditorScreen(projectName: String?) {
    EditorContent(projectName = projectName)
}

@Composable
fun EditorContent(projectName: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbBar(projectName = projectName)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        KeyboardAccessoryBar()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            EmptyState(
                title = "No file open",
                description = "File browsing and the Monaco editing engine are not integrated yet.",
                symbol = "</> ",
                badge = { MilestoneBadge("Monaco · M2 · files · M3") }
            )
        }
        StatusFooter(text = "Engine: none connected")
    }
}

@Composable
private fun BreadcrumbBar(projectName: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = projectName ?: "no project selected",
            fontFamily = PocketMonoFont,
            style = MaterialTheme.typography.labelLarge,
            color = if (projectName == null) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "/",
            fontFamily = PocketMonoFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "…",
            fontFamily = PocketMonoFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(8.dp))
        MilestoneBadge("breadcrumbs M3")
    }
}

/**
 * Static layout of the mobile keyboard accessory toolbar. Every key is
 * disabled and labeled until the IME bridge + Monaco land in M2 — the
 * layout/geometry of this bar is the deliverable here.
 */
@Composable
private fun KeyboardAccessoryBar() {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)
        ) {
            accessorySymbols.forEach { symbol ->
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Box(
                        modifier = Modifier.size(width = 34.dp, height = 30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = symbol,
                            fontFamily = PocketMonoFont,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Text(
            text = "Keyboard bridge + symbols activate with the Monaco editor (M2).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StatusFooter(text: String) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = PocketMonoFont,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

private val accessorySymbols = listOf("Tab", "Esc", "^", "(", ")", "[", "]", "{", "}", ";", "|", "↓")
