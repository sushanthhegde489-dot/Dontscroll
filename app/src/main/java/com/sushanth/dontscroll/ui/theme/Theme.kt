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

        // MAROON
        primary =
            MaroonLight,

        onPrimary =
            Color.White,

        primaryContainer =
            MaroonContainer,

        onPrimaryContainer =
            MaroonContainerLight,


        // TURQUOISE
        secondary =
            TurquoiseLight,

        onSecondary =
            Color(0xFF003735),

        secondaryContainer =
            TurquoiseContainer,

        onSecondaryContainer =
            TurquoiseContainerLight,


        // BACKGROUND
        background =
            DarkBackground,

        onBackground =
            DarkOnBackground,


        // SURFACE
        surface =
            DarkSurface,

        onSurface =
            DarkOnSurface,


        // SURFACE VARIANT
        surfaceVariant =
            DarkSurfaceVariant,

        onSurfaceVariant =
            DarkOnSurfaceVariant,

        surfaceContainer =
            DarkSurfaceContainer,


        // OUTLINES
        outline =
            DarkOutline,

        outlineVariant =
            DarkOutlineVariant,


        // ERROR
        error =
            Color(0xFFFFB4AB),

        onError =
            Color(0xFF690005),

        errorContainer =
            Color(0xFF93000A),

        onErrorContainer =
            Color(0xFFFFDAD6)
    )


// ============================================================
// LIGHT THEME
// ============================================================

private val DontscrollLightColorScheme =
    lightColorScheme(

        // MAROON
        primary =
            Maroon,

        onPrimary =
            Color.White,

        primaryContainer =
            MaroonContainerLight,

        onPrimaryContainer =
            MaroonDark,


        // TURQUOISE
        secondary =
            TurquoiseDark,

        onSecondary =
            Color.White,

        secondaryContainer =
            TurquoiseContainerLight,

        onSecondaryContainer =
            Color(0xFF003735),


        // BACKGROUND
        background =
            LightBackground,

        onBackground =
            LightOnBackground,


        // SURFACE
        surface =
            LightSurface,

        onSurface =
            LightOnSurface,


        // SURFACE VARIANT
        surfaceVariant =
            LightSurfaceVariant,

        onSurfaceVariant =
            LightOnSurfaceVariant,

        surfaceContainer =
            LightSurfaceContainer,


        // OUTLINES
        outline =
            LightOutline,

        outlineVariant =
            LightOutlineVariant,


        // ERROR
        error =
            Color(0xFFBA1A1A),

        onError =
            Color.White,

        errorContainer =
            Color(0xFFFFDAD6),

        onErrorContainer =
            Color(0xFF410002)
    )


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

        colorScheme =
            colorScheme,

        typography =
            DontscrollTypography,

        content =
            content
    )
}