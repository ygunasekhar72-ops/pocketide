package com.pocketide.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Success/warning tokens (mint family ties into the secondary accent). */
val PocketSuccess = Color(0xFF87DCA8)
val PocketOnSuccess = Color(0xFF0A2918)
val PocketSuccessContainer = Color(0xFF173A28)
val PocketOnSuccessContainer = Color(0xFFD6F5E3)
val PocketWarning = Color(0xFFF2C879)
val PocketOnWarning = Color(0xFF2E2407)
val PocketWarningContainer = Color(0xFF40331A)
val PocketOnWarningContainer = Color(0xFFFBEDD2)

/** Semantics Material 3 does not model natively. */
@Immutable
data class PocketExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color
)

val LocalPocketExtendedColors = staticCompositionLocalOf {
    PocketExtendedColors(
        success = Color.Unspecified, onSuccess = Color.Unspecified,
        successContainer = Color.Unspecified, onSuccessContainer = Color.Unspecified,
        warning = Color.Unspecified, onWarning = Color.Unspecified,
        warningContainer = Color.Unspecified, onWarningContainer = Color.Unspecified
    )
}

private val PocketDarkColors = darkColorScheme(
    primary = PocketPrimary,
    onPrimary = PocketOnPrimary,
    primaryContainer = PocketPrimaryContainer,
    onPrimaryContainer = PocketOnPrimaryContainer,
    secondary = PocketSecondary,
    onSecondary = PocketOnSecondary,
    secondaryContainer = PocketSecondaryContainer,
    onSecondaryContainer = PocketOnSecondaryContainer,
    background = PocketBackground,
    onBackground = PocketOnBackground,
    surface = PocketSurface,
    onSurface = PocketOnSurface,
    surfaceVariant = PocketSurfaceVariant,
    onSurfaceVariant = PocketOnSurfaceVariant,
    surfaceContainer = PocketSurfaceContainer,
    outline = PocketOutline,
    outlineVariant = PocketOutlineVariant,
    error = PocketError,
    onError = PocketOnError,
    errorContainer = PocketErrorContainer,
    onErrorContainer = PocketOnErrorContainer
)

private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Dark-first design system. The app is intentionally dark-only in M0.5. */
@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    val extended = PocketExtendedColors(
        success = PocketSuccess,
        onSuccess = PocketOnSuccess,
        successContainer = PocketSuccessContainer,
        onSuccessContainer = PocketOnSuccessContainer,
        warning = PocketWarning,
        onWarning = PocketOnWarning,
        warningContainer = PocketWarningContainer,
        onWarningContainer = PocketOnWarningContainer
    )
    CompositionLocalProvider(LocalPocketExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = PocketDarkColors,
            typography = PocketTypography,
            shapes = PocketShapes,
            content = content
        )
    }
}

/** Shortcut to extended semantic colors. */
object PocketThemeCompat {
    val extended: PocketExtendedColors
        @Composable get() = LocalPocketExtendedColors.current
}
