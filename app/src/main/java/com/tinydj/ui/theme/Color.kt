package com.tinydj.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class AppTheme { LIGHT, DARK, OLED }

object CurrentTheme {
    var currentTheme by mutableStateOf(AppTheme.LIGHT)

    val body: Color
        get() = when (currentTheme) {
            AppTheme.LIGHT -> Color(0xFFF2F1EC)
            AppTheme.DARK -> Color(0xFF2E2E2C)
            AppTheme.OLED -> Color(0xFF000000)
        }

    val ink: Color
        get() = when (currentTheme) {
            AppTheme.LIGHT -> Color(0xFF2E2E2C)
            AppTheme.DARK -> Color(0xFFF2F1EC)
            AppTheme.OLED -> Color(0xFFF2F1EC)
        }

    val backdrop: Color
        get() = when (currentTheme) {
            AppTheme.LIGHT -> Color(0xFFEFEFE9)
            AppTheme.DARK -> Color(0xFF20201F)
            AppTheme.OLED -> Color(0xFF000000)
        }

    val oledOn: Color
        get() = when (currentTheme) {
            AppTheme.LIGHT -> Color(0xFFEAF2EC)
            AppTheme.DARK -> Color(0xFF0B0D0E)
            AppTheme.OLED -> Color(0xFFEAF2EC)
        }

    val oledOff: Color
        get() = when (currentTheme) {
            AppTheme.LIGHT -> Color(0xFF0B0D0E)
            AppTheme.DARK -> Color(0xFFEAF2EC)
            AppTheme.OLED -> Color(0xFF000000)
        }
}

// Material entry points (kept in sync with [Metal] in DeviceStyle.kt). The chassis
// uses [Metal] directly; these feed the MaterialTheme color scheme + the library sheet.
// Hex values from BUILD SPEC A.3 (cool neutral anodized silver + orange accent).
val Body: Color get() = CurrentTheme.body
val BodyDark: Color get() = CurrentTheme.body
val BodyEdge: Color get() = CurrentTheme.ink
val Accent: Color get() = CurrentTheme.ink
val Ink: Color get() = CurrentTheme.ink

val BackdropColor: Color get() = CurrentTheme.backdrop
val OledOnColor: Color get() = CurrentTheme.oledOn
val OledOffColor: Color get() = CurrentTheme.oledOff
