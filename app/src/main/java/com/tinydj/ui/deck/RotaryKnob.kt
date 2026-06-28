package com.tinydj.ui.deck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tinydj.ui.theme.Metal

/**
 * The knurled volume / power crown — the diamond-knurled cylinder that protrudes from the
 * device's bottom-right corner (SPEC A.1 #13, pages 5 / 8 / 35). It is a wide, low rounded
 * rectangle straddling the bottom edge: a dark milled bezel ring around a tight diamond
 * cross-hatch, split into quadrants by a faint center seam, with a broad top-left highlight.
 *
 * Rolling it sets the master volume — right (clockwise) raises, left (counter-clockwise)
 * lowers — exactly like the reference power switch. Turning CCW past the bottom click turns
 * the device off; turning CW from off (or a tap) clicks it back on.
 *
 * **Local accumulator (no laggy committed read).** Volume is tracked in a local [level] seeded
 * from [volume] at drag start, so a fast roll never drops deltas waiting on a VM round-trip.
 * Each move reports the *delta* via [onVolumeChange]; the ViewModel owns the per-unit haptic
 * detents (one crisp tick per displayed volume unit crossed, 1:1). This widget deliberately
 * fires no haptics of its own — it only emits raw movement.
 */
@Composable
fun RotaryKnob(
    volume: Float,
    onVolumeChange: (delta: Float) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 52.dp,
    height: Dp = 30.dp,
) {
    val volState = rememberUpdatedState(volume)
    // Local accumulator so fast rolls don't drop deltas waiting on the VM round-trip.
    var level by remember { mutableFloatStateOf(volume) }

    Canvas(
        modifier
            .size(width = width, height = height)
            // Horizontal roll: right = CW = up, left = CCW = down (bottom-edge crown).
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        level = volState.value
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        // Pixels → volume fraction. A full sweep is ~ the crown's own width.
                        val step = dx / 240f
                        val before = level
                        level = (level + step).coerceIn(0f, 1f)
                        val delta = level - before
                        if (delta != 0f) onVolumeChange(delta)
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height

        // --- cylinder geometry: vertical straight sides with slightly rounded bottom ---
        val cylW = w
        val cylH = h
        val cylLeft = 0f
        val cylTop = 0f
        val cylCorner = 4.dp.toPx()
        val cylinderPath = Path().apply {
            moveTo(cylLeft, cylTop)
            lineTo(cylLeft + cylW, cylTop)
            lineTo(cylLeft + cylW, cylTop + cylH - cylCorner)
            quadraticTo(cylLeft + cylW, cylTop + cylH, cylLeft + cylW - cylCorner, cylTop + cylH)
            lineTo(cylLeft + cylCorner, cylTop + cylH)
            quadraticTo(cylLeft, cylTop + cylH, cylLeft, cylTop + cylH - cylCorner)
            close()
        }

        // Flat beige paper background fill
        drawPath(path = cylinderPath, color = Metal.knurl)

        // Diamond knurl grid pattern clipped to cylinder
        clipPath(cylinderPath) {
            val pitch = h * 0.18f
            val lineColor = Metal.seam
            val strokeW = 1.dp.toPx()
            val span = w + h
            // "/" diagonals
            var d = -h
            while (d < w) {
                drawLine(lineColor, Offset(d, 0f), Offset(d + h, h), strokeWidth = strokeW)
                d += pitch
            }
            // "\" diagonals
            d = 0f
            while (d < span) {
                drawLine(lineColor, Offset(d, 0f), Offset(d - h, h), strokeWidth = strokeW)
                d += pitch
            }
        }

        // Thin dark outline cylinder body
        drawPath(
            path = cylinderPath,
            color = Metal.seam,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}
