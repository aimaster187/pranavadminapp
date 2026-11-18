package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class Pranav3DTokens(
  val isDark: Boolean,
  val canvasGradientTop: Color,
  val canvasGradientBottom: Color,
  val cardBackground: Color,
  val cardHighlightRim: Color,
  val cardShadowTint: Color,
  val accentIce: Color,
  val accentPurple: Color,
  val subtleSurface: Color,
)

val LocalPranav3D =
  staticCompositionLocalOf {
    Pranav3DTokens(
      isDark = false,
      canvasGradientTop = GlacialMist,
      canvasGradientBottom = IceBlue50,
      cardBackground = GlacialWhite,
      cardHighlightRim = GlacialRimLight,
      cardShadowTint = Color(0x260284C7),
      accentIce = IceBlue500,
      accentPurple = RoyalPurple700,
      subtleSurface = GlacialSurfaceAlt,
    )
  }

private val LightColorScheme =
  lightColorScheme(
    primary = IceBlue600,
    onPrimary = GlacialWhite,
    primaryContainer = IceBlue100,
    onPrimaryContainer = IceBlue800,
    secondary = RoyalPurple700,
    onSecondary = GlacialWhite,
    secondaryContainer = RoyalPurple100,
    onSecondaryContainer = RoyalPurple900,
    tertiary = IceBlue400,
    background = GlacialMist,
    onBackground = SlateInk900,
    surface = GlacialWhite,
    onSurface = SlateInk900,
    surfaceVariant = GlacialSurfaceAlt,
    onSurfaceVariant = SlateInk600,
    outline = GlacialRimLight,
    error = CrimsonAlert,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = IceBlue400,
    onPrimary = ObsidianCanvas,
    primaryContainer = Color(0xFF0C2D48),
    onPrimaryContainer = IceBlue100,
    secondary = RoyalPurple400,
    onSecondary = ObsidianCanvas,
    secondaryContainer = Color(0xFF2B1C54),
    onSecondaryContainer = RoyalPurple100,
    tertiary = IceBlue500,
    background = ObsidianCanvas,
    onBackground = FrostInk100,
    surface = ObsidianSurface,
    onSurface = FrostInk100,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = FrostInk300,
    outline = ObsidianRim,
    error = CrimsonAlert,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val tokens =
    if (darkTheme) {
      Pranav3DTokens(
        isDark = true,
        canvasGradientTop = ObsidianCanvas,
        canvasGradientBottom = Color(0xFF0D1429),
        cardBackground = ObsidianSurface,
        cardHighlightRim = ObsidianRim,
        cardShadowTint = Color(0x55000000),
        accentIce = IceBlue400,
        accentPurple = RoyalPurple400,
        subtleSurface = ObsidianSurfaceElevated,
      )
    } else {
      Pranav3DTokens(
        isDark = false,
        canvasGradientTop = GlacialMist,
        canvasGradientBottom = IceBlue50,
        cardBackground = GlacialWhite,
        cardHighlightRim = GlacialRimLight,
        cardShadowTint = Color(0x280284C7),
        accentIce = IceBlue600,
        accentPurple = RoyalPurple700,
        subtleSurface = GlacialSurfaceAlt,
      )
    }

  CompositionLocalProvider(LocalPranav3D provides tokens) {
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
  }
}
