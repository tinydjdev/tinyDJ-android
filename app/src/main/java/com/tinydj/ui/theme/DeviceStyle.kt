package com.tinydj.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Shared visual language for the faithful hardware clone — a brushed-aluminum
 * device body with a silver machined reel, a tiny monochrome OLED,
 * rectangular transport keys, an edge rocker, LED VU meters and a knurled crown.
 *
 * Colors are read from the reference device (a cool, neutral anodized silver).
 * Keep every surface in this palette so the chassis, disc, keys, crown and meters
 * read as one machined object. The only saturated color is the orange [accent].
 */
object Metal {
    // Body / chassis (cream/beige paper).
    val body = Color(0xFFF2F1EC)
    val bodyLight = Color(0xFFF2F1EC)
    val bodyDark = Color(0xFFF2F1EC)
    val edgeHi = Color(0xFF2E2E2C)
    val edgeLo = Color(0xFF2E2E2C)
    val seam = Color(0xFF2E2E2C)
    val recess = Color(0xFFF2F1EC)
    val recessShadow = Color(0xFF2E2E2C)

    // Reel disc (cream/beige paper).
    val disc = Color(0xFFF2F1EC)
    val discLight = Color(0xFFF2F1EC)
    val discDark = Color(0xFFF2F1EC)
    val discGroove = Color(0xFF2E2E2C)
    val hairline = Color(0xFF2E2E2C)
    val microText = Color(0xFF2E2E2C)

    // Spindle plate (cream/beige paper) + dimples.
    val spindle = Color(0xFFF2F1EC)
    val spindleDimple = Color(0xFF2E2E2C)

    // Keys / buttons.
    val key = Color(0xFFF2F1EC)
    val keyLight = Color(0xFFF2F1EC)
    val keyDark = Color(0xFFF2F1EC)
    val keyInk = Color(0xFF2E2E2C)

    // Knurled crown.
    val knurl = Color(0xFFF2F1EC)
    val knurlLight = Color(0xFFF2F1EC)
    val knurlDark = Color(0xFF2E2E2C)

    // Accent.
    val accent = Color(0xFF2E2E2C)
    val accentDim = Color(0xFF2E2E2C)
    val accentInk = Color(0xFFF2F1EC)

    // Etched wordmark + engraved spec text.
    val wordmark = Color(0xFF2E2E2C)

    // Monochrome OLED.
    val lcdBg = Color(0xFF2E2E2C)
    val lcdBezel = Color(0xFF2E2E2C)
    val lcdOn = Color(0xFFF2F1EC)
    val lcdDim = Color(0xFF383836)
    val lcdBox = Color(0xFFF2F1EC)

    // Status LED.
    val ledRest = Color(0xFF2E2E2C)
    val ledRed = Color(0xFF2E2E2C)
    val ledWhite = Color(0xFF2E2E2C)

    // VU meters.
    val vuOff = Color(0xFFF2F1EC)
    val vuOn = Color(0xFF2E2E2C)
    val vuPeak = Color(0xFF2E2E2C)

    val ink = Color(0xFF2E2E2C)
}

/** Top-lit brushed-metal fill for vertical surfaces (chassis, keys). */
fun brushedVertical(
    light: Color = Metal.bodyLight,
    mid: Color = Metal.body,
    dark: Color = Metal.bodyDark,
): Brush = Brush.verticalGradient(0f to mid, 1f to mid)

/** Raised-key fill: flat fill. */
fun keyFill(): Brush = Brush.verticalGradient(
    0f to Metal.key,
    1f to Metal.key,
)

/** Recessed-well fill: flat fill. */
fun wellFill(): Brush = Brush.verticalGradient(
    0f to Metal.recess,
    1f to Metal.recess,
)
