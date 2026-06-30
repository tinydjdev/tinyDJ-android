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
    val body: Color get() = Body
    val bodyLight: Color get() = Body
    val bodyDark: Color get() = Body
    val edgeHi: Color get() = Ink
    val edgeLo: Color get() = Ink
    val seam: Color get() = Ink
    val recess: Color get() = Body
    val recessShadow: Color get() = Ink

    // Reel disc (cream/beige paper).
    val disc: Color get() = Body
    val discLight: Color get() = Body
    val discDark: Color get() = Body
    val discGroove: Color get() = Ink
    val hairline: Color get() = Ink
    val microText: Color get() = Ink

    // Spindle plate (cream/beige paper) + dimples.
    val spindle: Color get() = Body
    val spindleDimple: Color get() = Ink

    // Keys / buttons.
    val key: Color get() = Body
    val keyLight: Color get() = Body
    val keyDark: Color get() = Body
    val keyInk: Color get() = Ink

    // Knurled crown.
    val knurl: Color get() = Body
    val knurlLight: Color get() = Body
    val knurlDark: Color get() = Ink

    // Accent.
    val accent: Color get() = Ink
    val accentDim: Color get() = Ink
    val accentInk: Color get() = Body

    // Etched wordmark + engraved spec text.
    val wordmark: Color get() = Ink

    // Monochrome OLED.
    val lcdBg: Color get() = OledOffColor
    val lcdBezel: Color get() = Ink
    val lcdOn: Color get() = OledOnColor
    val lcdDim: Color get() = OledOffColor
    val lcdBox: Color get() = OledOnColor

    // Status LED.
    val ledRest: Color get() = Ink
    val ledRed: Color get() = Ink
    val ledWhite: Color get() = Ink

    // VU meters.
    val vuOff: Color get() = Body
    val vuOn: Color get() = Ink
    val vuPeak: Color get() = Ink

    val ink: Color get() = Ink
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
