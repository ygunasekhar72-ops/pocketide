package com.pocketide.mobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.mobile.ui.components.MonoIcon
import com.pocketide.mobile.ui.navigation.PocketDestination
import com.pocketide.mobile.ui.theme.PocketMonoFont

/** Contextual top bar: screen title + optional project context line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketTopBar(
    destination: PocketDestination,
    contextProject: String?,
    modifier: Modifier = Modifier
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        ),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (contextProject != null) {
                    Text(
                        text = contextProject,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = PocketMonoFont,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    )
}

@Composable
private fun TabIcon(destination: PocketDestination) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        MonoIcon(symbol = destination.symbol, modifier = Modifier.fillMaxSize())
    }
}

/** Bottom navigation for the five shell destinations. */
@Composable
fun PocketBottomNav(
    current: PocketDestination,
    onSelect: (PocketDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        PocketDestination.bottomTabs.forEach { tab ->
            NavigationBarItem(
                selected = tab.id == current.id,
                onClick = { onSelect(tab) },
                icon = { TabIcon(tab) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}
