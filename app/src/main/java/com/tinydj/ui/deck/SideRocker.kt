package com.tinydj.ui.deck

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tinydj.ui.theme.Metal

/**
 * The left-edge **rocker** — a long, slim see-saw lever lying vertically along the left wall of
 * the chassis (reference manual pages 6, 9, 10). It is a single milled part with rounded pill
 * ends and a pinched waist around a central pivot dimple. Press and hold the **top** half to
 * fast-forward, the **bottom** half to rewind; both spin the reel (CW for FF, CCW for REW) and
 * scan the audio for as long as the half is held.
 *
 * The engraved markings sit *inboard* on the face plate, fixed regardless of which side is
 * pressed: `▲▲` near the top (fast-forward) and `R▼` near the bottom (rewind).
 *
 * [onScanStart] is invoked with `forward = true` for the top half (FF) and `forward = false`
 * for the bottom half (REW); [onScanStop] fires on release. The lever visibly tilts on its
 * pivot while a side is held — top-in/bottom-out for FF, bottom-in/top-out for REW.
 */
@Composable
fun SideRocker(
    onScanStart: (forward: Boolean) -> Unit,
    onScanStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // pressedSide: 0 = none, +1 = top (FF), −1 = bottom (REW).
    var pressedSide by remember { mutableStateOf(0) }
    // Smoothly tilt the lever about its pivot while a half is held.
    val tilt by animateFloatAsState(pressedSide.toFloat(), label = "rockerTilt")

    Box(
        modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pos ->
                        // Top half of the lever fast-forwards, bottom half rewinds.
                        val forward = pos.y < size.height / 2f
                        pressedSide = if (forward) 1 else -1
                        onScanStart(forward)
                        tryAwaitRelease()
                        pressedSide = 0
                        onScanStop()
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawRocker(tilt) }
    }
}

/**
 * Paint the see-saw lever and its inboard engravings. [tilt] in [-1, 1] rocks the lever about
 * its pivot: +1 dips the top half inward (FF), −1 dips the bottom half inward (REW).
 *
 * The component's width is split: the lever rides the *outer* (left) portion, hugging the
 * chassis wall, and the `▲▲` / `R▼` marks are etched on the *inner* (right) portion.
 */
private fun DrawScope.drawRocker(tilt: Float) {
    val w = size.width
    val h = size.height

    val leverW = w * 0.75f                  // lever bar width
    val leverCx = w * 0.5f                  // center in the component
    val pivotY = h * 0.50f                  // see-saw pivot at mid-height
    val topEnd = h * 0.085f                 // top pill-end center
    val botEnd = h * 0.915f                 // bottom pill-end center
    val halfW = leverW / 2f

    val tiltDeg = tilt * 4.5f
    rotate(degrees = tiltDeg, pivot = Offset(leverCx, pivotY)) {
        drawLeverBody(leverCx, halfW, topEnd, botEnd, pivotY)
        drawPivot(leverCx, pivotY, leverW)
    }
}

/**
 * The slim milled bar: two pill-ended halves meeting at a pinched waist around the pivot, drawn
 * as flat 2D lines and strokes.
 */
private fun DrawScope.drawLeverBody(
    cx: Float,
    halfW: Float,
    topEnd: Float,
    botEnd: Float,
    pivotY: Float,
) {
    val waist = halfW * 0.46f
    val waistSpan = (botEnd - topEnd) * 0.10f
    val left = cx - halfW
    val right = cx + halfW

    val body = Path().apply {
        moveTo(left, topEnd)
        lineTo(left, botEnd)
        cubicTo(left, botEnd + halfW, right, botEnd + halfW, right, botEnd)
        lineTo(right, pivotY + waistSpan)
        quadraticTo(cx + waist, pivotY, right, pivotY - waistSpan)
        lineTo(right, topEnd)
        cubicTo(right, topEnd - halfW, left, topEnd - halfW, left, topEnd)
        close()
    }

    // Flat beige body fill
    drawPath(body, color = Metal.key)
    // Simple thin dark outline
    drawPath(body, color = Metal.edgeLo, style = Stroke(width = 1.dp.toPx()))
}

/** The central pivot: a small simple outline circle representing the center pivot pinhole. */
private fun DrawScope.drawPivot(cx: Float, cy: Float, leverW: Float) {
    val r = leverW * 0.16f
    // Simple outline circle representing the center pivot pinhole
    drawCircle(Metal.edgeLo, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
}

/** `▲▲` engraving — two small stacked up-triangles (fast forward). */
private fun DrawScope.drawFfMark(cx: Float, cy: Float, w: Float) {
    val s = w * 0.16f
    val gap = s * 0.55f
    triangle(cx, cy - gap, s, up = true)
    triangle(cx, cy + gap, s, up = true)
}

/**
 * `R▼` engraving — the letter `R` (rotated to read down the edge, as on the device) above a small
 * down-triangle (rewind).
 */
private fun DrawScope.drawRewMark(cx: Float, cy: Float, w: Float) {
    val s = w * 0.16f
    // The down-triangle on top…
    triangle(cx, cy - s * 0.55f, s, up = false)
    // …then the sideways `R` below it.
    translate(left = cx, top = cy + s * 0.65f) {
        rotate(degrees = -90f, pivot = Offset.Zero) {
            drawLetterR(0f, 0f, s * 1.1f)
        }
    }
}

/** A small filled engraved triangle centered at (cx, cy). */
private fun DrawScope.triangle(cx: Float, cy: Float, s: Float, up: Boolean) {
    val hw = s * 0.5f
    val hh = s * 0.5f
    val p = Path().apply {
        if (up) {
            moveTo(cx, cy - hh)
            lineTo(cx - hw, cy + hh)
            lineTo(cx + hw, cy + hh)
        } else {
            moveTo(cx, cy + hh)
            lineTo(cx - hw, cy - hh)
            lineTo(cx + hw, cy - hh)
        }
        close()
    }
    drawPath(p, Metal.microText)
}

/**
 * A minimal engraved capital `R`, drawn centered at (x, y) within a box of side [s]. Stroked in
 * the face spec-text color so it matches the device's etched markings.
 */
private fun DrawScope.drawLetterR(x: Float, y: Float, s: Float) {
    val sw = (s * 0.16f).coerceAtLeast(1f)
    val left = x - s * 0.32f
    val right = x + s * 0.30f
    val top = y - s * 0.5f
    val bot = y + s * 0.5f
    val mid = y
    val col = Metal.microText

    // Left stem.
    drawLine(col, Offset(left, top), Offset(left, bot), strokeWidth = sw, cap = StrokeCap.Round)
    // Upper bowl (left stem → arc → back to the waist).
    val bowl = Path().apply {
        moveTo(left, top)
        lineTo(right - s * 0.12f, top)
        arcTo(
            rect = Rect(left, top, right + s * 0.06f, mid),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 180f,
            forceMoveTo = false,
        )
        lineTo(left, mid)
    }
    drawPath(bowl, col, style = Stroke(width = sw, cap = StrokeCap.Round))
    // Diagonal leg.
    drawLine(col, Offset(left + s * 0.10f, mid), Offset(right, bot), strokeWidth = sw, cap = StrokeCap.Round)
}
