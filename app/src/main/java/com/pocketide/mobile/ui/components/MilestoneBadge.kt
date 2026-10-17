package com.pocketide.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.mobile.ui.theme.PocketThemeCompat

/**
 * Small pill marking a capability that arrives in a later milestone.
 * Used to keep placeholder controls honest without hiding them entirely.
 */
@Composable
fun MilestoneBadge(milestone: String, modifier: Modifier = Modifier) {
    val colors = PocketThemeCompat.extended
    Box(
        modifier = modifier.background(
            color = colors.warningContainer,
            shape = MaterialTheme.shapes.extraSmall
        )
    ) {
        Text(
            text = milestone,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onWarningContainer,
            fontWeight = FontWeight.Medium
        )
    }
}
