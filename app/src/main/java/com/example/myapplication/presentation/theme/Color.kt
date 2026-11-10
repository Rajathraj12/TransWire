package com.example.myapplication.presentation.theme

import androidx.compose.ui.graphics.Color

// ── Brand Primaries ──────────────────────────────────────────────────────────
val CobaltBlue        = Color(0xFF1459F5)   // Send / primary action
val CobaltBlueDark    = Color(0xFF4C82FF)   // Dark mode primary
val SignalAmber       = Color(0xFFF06A1A)   // Receive / secondary action
val SignalAmberDark   = Color(0xFFFF8533)   // Dark mode secondary

// ── Light Scheme ─────────────────────────────────────────────────────────────
val LightPrimary                = CobaltBlue
val LightOnPrimary              = Color(0xFFFFFFFF)
val LightPrimaryContainer       = Color(0xFFDAE5FF)
val LightOnPrimaryContainer     = Color(0xFF001355)

val LightSecondary              = SignalAmber
val LightOnSecondary            = Color(0xFFFFFFFF)
val LightSecondaryContainer     = Color(0xFFFFDDBF)
val LightOnSecondaryContainer   = Color(0xFF301500)

val LightTertiary               = Color(0xFF0E9F6E)   // Success / completed
val LightOnTertiary             = Color(0xFFFFFFFF)
val LightTertiaryContainer      = Color(0xFFBCF5DA)
val LightOnTertiaryContainer    = Color(0xFF00341E)

val LightError                  = Color(0xFFDC2626)
val LightOnError                = Color(0xFFFFFFFF)
val LightErrorContainer         = Color(0xFFFFDAD6)
val LightOnErrorContainer       = Color(0xFF410002)

val LightBackground             = Color(0xFFF5F6F8)
val LightOnBackground           = Color(0xFF0F172A)

val LightSurface                = Color(0xFFFFFFFF)
val LightOnSurface              = Color(0xFF0F172A)
val LightSurfaceVariant         = Color(0xFFECEFF4)
val LightOnSurfaceVariant       = Color(0xFF526075)

val LightOutline                = Color(0xFFDCE1EA)
val LightOutlineVariant         = Color(0xFFEEF1F5)
val LightInverseSurface         = Color(0xFF1B2230)
val LightInverseOnSurface       = Color(0xFFF0F4FF)
val LightInversePrimary         = Color(0xFF4C82FF)
val LightSurfaceTint            = CobaltBlue

// ── Dark Scheme ───────────────────────────────────────────────────────────────
val DarkPrimary                 = CobaltBlueDark
val DarkOnPrimary               = Color(0xFF001355)
val DarkPrimaryContainer        = Color(0xFF0E32A0)
val DarkOnPrimaryContainer      = Color(0xFFDAE5FF)

val DarkSecondary               = SignalAmberDark
val DarkOnSecondary             = Color(0xFF301500)
val DarkSecondaryContainer      = Color(0xFF7A3300)
val DarkOnSecondaryContainer    = Color(0xFFFFDDBF)

val DarkTertiary                = Color(0xFF34D399)
val DarkOnTertiary              = Color(0xFF003822)
val DarkTertiaryContainer       = Color(0xFF005C37)
val DarkOnTertiaryContainer     = Color(0xFFB2FCDA)

val DarkError                   = Color(0xFFF87171)
val DarkOnError                 = Color(0xFF690005)
val DarkErrorContainer          = Color(0xFF93000A)
val DarkOnErrorContainer        = Color(0xFFFFDAD6)

val DarkBackground              = Color(0xFF0B0E14)
val DarkOnBackground            = Color(0xFFF0F4FF)

val DarkSurface                 = Color(0xFF141923)
val DarkOnSurface               = Color(0xFFE8EDFB)
val DarkSurfaceVariant          = Color(0xFF1B2230)
val DarkOnSurfaceVariant        = Color(0xFF8B9CB5)

val DarkOutline                 = Color(0xFF242C3D)
val DarkOutlineVariant          = Color(0xFF1E2535)
val DarkInverseSurface          = Color(0xFFE8EDFB)
val DarkInverseOnSurface        = Color(0xFF141923)
val DarkInversePrimary          = CobaltBlue
val DarkSurfaceTint             = CobaltBlueDark

// ── Semantic Stand-alone (use sparingly, prefer MaterialTheme.colorScheme) ──
val SemanticWarning             = Color(0xFFD97706)
val SemanticWarningDark         = Color(0xFFFBBF24)
val SemanticPaused              = Color(0xFF7C8FA6)
val SemanticPausedDark          = Color(0xFF8B9CB5)
