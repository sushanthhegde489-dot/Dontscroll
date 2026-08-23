package com.sushanth.dontscroll.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color


// ============================================================
// DARK THEME
// ============================================================

private val DontscrollDarkColorScheme =
    darkColorScheme(

        primary = MaroonLight,
        onPrimary = Color.White,

        primaryContainer = MaroonContainer,
        onPrimaryContainer = MaroonContainerLight,

        // No teal.
        secondary = RoseLight,
        onSecondary = Color(0xFF3B0718),

        secondaryContainer = Color(0xFF5A1C30),
        onSecondaryContainer = Color(0xFFFFD9E2),

        tertiary = PeachLight,
        onTertiary = Color(0xFF3A0E06),

        tertiaryContainer = Color(0xFF633021),
        onTertiaryContainer = Color(0xFFFFDBD0),

        background = DarkBackground,
        onBackground = DarkOnBackground,

        surface = DarkSurface,
        onSurface = DarkOnSurface,

        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,

        surfaceContainer = DarkSurfaceContainer,

        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,

        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),

        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6)
    )


// ============================================================
// LIGHT THEME
// ============================================================

private val DontscrollLightColorScheme =
    lightColorScheme(

        primary = Maroon,
        onPrimary = Color.White,

        primaryContainer = MaroonContainerLight,
        onPrimaryContainer = MaroonDark,

        // No teal.
        secondary = RoseDark,
        onSecondary = Color.White,

        secondaryContainer = Color(0xFFFFD9E2),
        onSecondaryContainer = Color(0xFF4A0B20),

        tertiary = PeachDark,
        onTertiary = Color.White,

        tertiaryContainer = Color(0xFFFFDBD0),
        onTertiaryContainer = Color(0xFF4A160D),

        background = LightBackground,
        onBackground = LightOnBackground,

        surface = LightSurface,
        onSurface = LightOnSurface,

        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,

        surfaceContainer = LightSurfaceContainer,

        outline = LightOutline,
        outlineVariant = LightOutlineVariant,

        error = Color(0xFFBA1A1A),
        onError = Color.White,

        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002)
    )


// ============================================================
// THEME
// ============================================================

@Composable
fun DontscrollTheme(

    darkTheme: Boolean =
        isSystemInDarkTheme(),

    content: @Composable () -> Unit

) {

    val colorScheme =
        if (darkTheme) {

            DontscrollDarkColorScheme

        } else {

            DontscrollLightColorScheme
        }

    MaterialTheme(

        colorScheme = colorScheme,

        typography = DontscrollTypography,

        content = content
    )
}