package com.tinydj.ui.deck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tinydj.ui.theme.Metal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The motorized tape reel — drawn to match the device exactly (BUILD SPEC A.1.2/A.1.3,
 * manual pages 3, 9, 36):
 *
 *  - a large, bright, flat machined-aluminum disc with a faint concentric turned sheen and a
 *    single thin outer rim ring;
 *  - one thin straight index / azimuth hairline running the full diameter (12 → 6 o'clock),
 *    which rotates with the platter;
 *  - a small open ring at 12 o'clock on the rim and a tiny solid index dot just left of it,
 *    the two fixed engraved face markings `96 / 24` (left) and `3 ⊕ M` (right), and the
 *    lower-right partial concentric arc;
 *  - a flat, bright center spindle plate (motor hub cap) with its own outer ring and the exact
 *    three-dimple pattern — one at 12 o'clock on the index line, two lower (≈7–8 and ≈4–5
 *    o'clock) forming an up-pointing isoceles triangle, the index line passing vertically
 *    between the top dimple and the bottom pair. NOT a d-pad, NOT a dull grey gradient.
 *
 * ## Interaction (CONTRACT §7 — the platter is a single multi-function dial)
 * This composable is intentionally "dumb": it reports RAW finger movement and never decides
 * what a turn means. Grab fires [onHoldStart]; every rotation step fires
 * [onRotate]`(dirRight, delta)` where `delta` is the unsigned degrees turned this frame and
 * `dirRight` is the sign (clockwise = right); lift fires [onHoldEnd]. The [DeckViewModel] owns
 * the routing (scrub / scratch / varispeed / menu-scroll / delete-toggle / reset-confirm) AND
 * the per-unit value haptics, so a six-unit value move ticks six times no matter which dial
 * produced it. We deliberately do NOT fire one detent per N degrees here — that was the old
 * "buzz once per multi-unit move" bug, now owned end-to-end by the VM.
 *
 * @param rotationDeg motor-driven platter angle (degrees); used when the finger is off the reel.
 * @param spinning whether the platter is being driven by playback (reserved for callers that
 *                 want to vary the visual; the angle itself comes from [rotationDeg]).
 * @param motorEnabled MOTOR setting gate: when false the platter does not free-spin with the
 *                 motor (it parks), though the finger still drives the reel for menu/pause.
 */
@Composable
fun ReelDisc(
    rotationDeg: Float,
    spinning: Boolean,
    motorEnabled: Boolean,
    onRotate: (dirRight: Boolean, delta: Float) -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // While the finger owns the platter we drive the visual angle ourselves so the disc tracks
    // the touch 1:1; otherwise the motor angle ([rotationDeg]) drives it.
    var dragAngle by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val onRotateState = rememberUpdatedState(onRotate)
    val onHoldStartState = rememberUpdatedState(onHoldStart)
    val onHoldEndState = rememberUpdatedState(onHoldEnd)

    val gesture = Modifier.pointerInput(Unit) {
        val center = Offset(size.width / 2f, size.height / 2f)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            dragging = true
            dragAngle = rotationDeg
            onHoldStartState.value()                       // grab: VM begins scrub / hold-pause
            var prevA = atan2(
                (down.position.y - center.y).toDouble(),
                (down.position.x - center.x).toDouble(),
            )
            while (true) {
                val event = awaitPointerEvent()
                val ch = event.changes.firstOrNull() ?: break
                if (!ch.pressed) break
                val a = atan2(
                    (ch.position.y - center.y).toDouble(),
                    (ch.position.x - center.x).toDouble(),
                )
                var dA = a - prevA
                if (dA > PI) dA -= 2 * PI
                if (dA < -PI) dA += 2 * PI
                val dDeg = Math.toDegrees(dA).toFloat()
                if (dDeg != 0f) {
                    dragAngle += dDeg
                    // Raw movement only: sign + magnitude. The VM converts this to discrete
                    // units (scrub speed / value steps) and fires the per-unit ticks.
                    onRotateState.value(dDeg > 0f, abs(dDeg))
                }
                prevA = a
                ch.consume()
            }
            dragging = false
            onHoldEndState.value()                         // lift: VM ends scrub / resumes
        }
    }

    Canvas(modifier.fillMaxWidth().aspectRatio(1f).then(gesture)) {
        val c = center
        val r = size.minDimension / 2f
        val angle = when {
            dragging -> dragAngle
            motorEnabled -> rotationDeg
            else -> 0f
        }

        // --- Disc face: flat beige paper with a thin outline ---
        val discR = r * 0.93f
        drawCircle(Metal.disc, radius = discR, center = c)
        drawCircle(Metal.discGroove, radius = discR, center = c, style = Stroke(1.dp.toPx()))

        // --- Rotating layer: wheel indices, spec texts, and split azimuth hairline ---
        rotate(angle, c) {
            faceText(c, Offset(-discR * 0.55f, 0f), "96 / 24", discR * 0.072f, Metal.microText)
            faceText(c, Offset(discR * 0.44f, 0f), "3", discR * 0.072f, Metal.microText)
            faceText(c, Offset(discR * 0.68f, 0f), "M", discR * 0.072f, Metal.microText)
            doubleRing(Offset(c.x + discR * 0.55f, c.y), discR * 0.035f, Metal.microText)

            val topStart = c.y - discR
            val topEnd = c.y - discR * 0.19f
            val botStart = c.y + discR * 0.19f
            val botEnd = c.y + discR
            drawLine(
                Metal.hairline,
                Offset(c.x, topStart),
                Offset(c.x, topEnd),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Butt,
            )
            drawLine(
                Metal.hairline,
                Offset(c.x, botStart),
                Offset(c.x, botEnd),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Butt,
            )
        }

        // --- Center spindle plate (motor hub cap): flat, outer ring, center ring + 3 dimples ---
        spindle(c, discR * 0.19f, angle)
    }
}

private fun DrawScope.spindle(c: Offset, plateR: Float, angleDeg: Float) {
    drawCircle(Metal.spindle, radius = plateR, center = c)
    drawCircle(Metal.discGroove, radius = plateR, center = c, style = Stroke(1.dp.toPx()))
    // Center circle (axle cap)
    drawCircle(Metal.discGroove, radius = plateR * 0.2f, center = c, style = Stroke(1.dp.toPx()))

    val dimpleR = plateR * 0.12f
    val out = plateR * 0.5f
    fun dimpleAt(clockDeg: Float, out: Float) {
        val rad = Math.toRadians((angleDeg - 90f + clockDeg).toDouble())
        val p = Offset(c.x + (out * cos(rad)).toFloat(), c.y + (out * sin(rad)).toFloat())
        drawCircle(Metal.spindleDimple, radius = dimpleR, center = p, style = Stroke(1.dp.toPx()))
    }
    dimpleAt(0f, out)
    dimpleAt(-120f, out)
    dimpleAt(120f, out)
}

private fun DrawScope.doubleRing(p: Offset, radius: Float, color: Color) {
    val gap = radius * 0.4f
    drawCircle(color, radius * 0.7f, Offset(p.x - gap, p.y), style = Stroke(1.dp.toPx()))
    drawCircle(color, radius * 0.7f, Offset(p.x + gap, p.y), style = Stroke(1.dp.toPx()))
}

/** Engraved, non-rotating monospace face text centered at [c] + [offset]. */
private fun DrawScope.faceText(c: Offset, offset: Offset, text: String, size: Float, color: Color) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            textSize = size
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val fm = paint.fontMetrics
        val baseline = c.y + offset.y - (fm.ascent + fm.descent) / 2f
        canvas.nativeCanvas.drawText(text, c.x + offset.x, baseline, paint)
    }
}
