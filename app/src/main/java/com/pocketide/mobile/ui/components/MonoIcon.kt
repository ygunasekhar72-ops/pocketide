package com.pocketide.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.pocketide.mobile.ui.theme.PocketMonoFont

/**
 * Vertically centered monospace glyph icon. Used where Material's core icon
 * set has no fitting symbol (terminal, assistant) so we stay dependency-light
 * (no extended icon pack).
 */
@Composable
fun MonoIcon(symbol: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = symbol,
            fontFamily = PocketMonoFont,
            fontWeight = FontWeight.Bold,
            color = LocalContentColor.current
        )
    }
}
