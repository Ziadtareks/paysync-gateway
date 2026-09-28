package com.paysync.gateway.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ─── Slate & Neutral Tones ───────────────────────────────────────────
val Slate50  = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate850 = Color(0xFF162032)
val Slate900 = Color(0xFF0F172A)
val Slate950 = Color(0xFF0A0F1D)

// ─── Brand & Accent Colors ───────────────────────────────────────────
val Indigo50  = Color(0xFFEEF2FF)
val Indigo100 = Color(0xFFE0E7FF)
val Indigo200 = Color(0xFFC7D2FE)
val Indigo400 = Color(0xFF818CF8)
val Indigo500 = Color(0xFF6366F1)
val Indigo600 = Color(0xFF4F46E5)
val Indigo700 = Color(0xFF4338CA)
val Indigo900 = Color(0xFF312E81)

val Teal300 = Color(0xFF5EEAD4)
val Teal400 = Color(0xFF2DD4BF)
val Teal500 = Color(0xFF14B8A6)
val Teal600 = Color(0xFF0D9488)
val Cyan400 = Color(0xFF22D3EE)
val Cyan500 = Color(0xFF06B6D4)
val Sky500  = Color(0xFF0EA5E9)

// ─── Semantic Status Colors ──────────────────────────────────────────
val StatusGreen      = Color(0xFF10B981)
val StatusGreenLight = Color(0xFFECFDF5)
val StatusGreenDark  = Color(0xFF064E3B)

val StatusRed      = Color(0xFFEF4444)
val StatusRedLight = Color(0xFFFEF2F2)
val StatusRedDark  = Color(0xFF7F1D1D)

val StatusAmber      = Color(0xFFF59E0B)
val StatusAmberLight = Color(0xFFFFFBEB)
val StatusAmberDark  = Color(0xFF78350F)

// ─── Material 3 Light Scheme Tokens ─────────────────────────────────
val LightPrimary             = Indigo600
val LightOnPrimary           = Color(0xFFFFFFFF)
val LightPrimaryContainer    = Indigo50
val LightOnPrimaryContainer  = Indigo900

val LightSecondary             = Teal600
val LightOnSecondary           = Color(0xFFFFFFFF)
val LightSecondaryContainer    = Color(0xFFCCFBF1)
val LightOnSecondaryContainer  = Color(0xFF115E59)

val LightTertiary             = Cyan500
val LightOnTertiary           = Color(0xFFFFFFFF)
val LightTertiaryContainer    = Color(0xFFCFFAFE)
val LightOnTertiaryContainer  = Color(0xFF164E63)

val LightError             = StatusRed
val LightOnError           = Color(0xFFFFFFFF)
val LightErrorContainer    = StatusRedLight
val LightOnErrorContainer  = Color(0xFF991B1B)

val LightBackground        = Slate50
val LightOnBackground      = Slate900
val LightSurface           = Color(0xFFFFFFFF)
val LightOnSurface         = Slate900
val LightSurfaceVariant    = Slate100
val LightOnSurfaceVariant  = Slate600
val LightOutline           = Slate300
val LightOutlineVariant    = Slate200

// ─── Material 3 Dark Scheme Tokens (Deep Slate / Navy) ──────────────
val DarkPrimary             = Indigo400
val DarkOnPrimary           = Color(0xFF1E1B4B)
val DarkPrimaryContainer    = Indigo900
val DarkOnPrimaryContainer  = Indigo100

val DarkSecondary             = Teal400
val DarkOnSecondary           = Color(0xFF042F2E)
val DarkSecondaryContainer    = Color(0xFF134E4A)
val DarkOnSecondaryContainer  = Color(0xFFCCFBF1)

val DarkTertiary             = Cyan400
val DarkOnTertiary           = Color(0xFF082F49)
val DarkTertiaryContainer    = Color(0xFF164E63)
val DarkOnTertiaryContainer  = Color(0xFFCFFAFE)

val DarkError             = Color(0xFFF87171)
val DarkOnError           = Color(0xFF450A0A)
val DarkErrorContainer    = StatusRedDark
val DarkOnErrorContainer  = Color(0xFFFEE2E2)

val DarkBackground        = Slate950
val DarkOnBackground      = Slate50
val DarkSurface           = Slate900
val DarkOnSurface         = Slate100
val DarkSurfaceVariant    = Slate800
val DarkOnSurfaceVariant  = Slate400
val DarkOutline           = Slate600
val DarkOutlineVariant    = Slate700

// ─── Premium Linear Gradients ────────────────────────────────────────
val GatewayRunningGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF059669), // Emerald 600
        Color(0xFF0D9488), // Teal 600
        Color(0xFF0284C7)  // Sky 600
    )
)

val GatewayStoppedGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF1E293B), // Slate 800
        Color(0xFF334155), // Slate 700
        Color(0xFF881337)  // Rose 900
    )
)

val PrimaryGradient = Brush.horizontalGradient(
    colors = listOf(Indigo600, Cyan500)
)

val CardGlowRunning = Color(0xFF10B981).copy(alpha = 0.25f)
val CardGlowStopped = Color(0xFFEF4444).copy(alpha = 0.20f)
