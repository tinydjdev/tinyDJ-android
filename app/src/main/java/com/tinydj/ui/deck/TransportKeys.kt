package com.tinydj.ui.deck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tinydj.ui.theme.Metal

/**
 * The three transport keys in flat wireframe style.
 */
@Composable
fun TransportKeys(
    isPlaying: Boolean,
    recArmed: Boolean,
    onRecord: () -> Unit,
    onRecordHold: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onStopHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "recFlash")
    val flashAlpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val recordColor = if (recArmed) {
        lerp(Metal.keyInk, Color.Red, flashAlpha)
    } else {
        Metal.keyInk
    }

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = KEY_BAR_HEIGHT)
            .fillMaxHeight()
            .drawBehind {
                // Top line of the bounding box
                drawLine(
                    color = Metal.seam,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
                // Right line of the bounding box
                drawLine(
                    color = Metal.seam,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TransportKey(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onTap = onRecord,
                onHold = onRecordHold,
            ) { recordGlyph(recordColor) }

            TransportKey(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onTap = onPlay,
                onHold = null,
            ) { playGlyph() }

            TransportKey(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onTap = onStop,
                onHold = onStopHold,
            ) { stopGlyph() }
        }
    }
}

@Composable
private fun TransportKey(
    modifier: Modifier,
    onTap: () -> Unit,
    onHold: (() -> Unit)?,
    glyph: DrawScope.() -> Unit,
) {
    val keyShape = RoundedCornerShape(4.dp)

    Box(
        modifier
            .clip(keyShape)
            .background(Metal.key)
            .border(1.dp, Metal.seam, keyShape)
            .pointerInput(onHold) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = if (onHold != null) { { _ -> onHold() } } else null,
                    onPress = {
                        tryAwaitRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            glyph()
        }
    }
}

private fun DrawScope.glyphCenter(): Offset = Offset(size.width / 2f, size.height * 0.46f)

private fun DrawScope.recordGlyph(color: Color) {
    val c = glyphCenter()
    val r = size.minDimension * 0.10f
    drawCircle(color, r, c)
}

private fun DrawScope.playGlyph() {
    val c = glyphCenter()
    val s = size.minDimension * 0.12f
    val p = Path().apply {
        moveTo(c.x - s * 0.62f, c.y - s)
        lineTo(c.x - s * 0.62f, c.y + s)
        lineTo(c.x + s * 0.78f, c.y)
        close()
    }
    drawPath(p, Metal.keyInk)
}

private fun DrawScope.stopGlyph() {
    val c = glyphCenter()
    val s = size.minDimension * 0.18f
    drawRect(Metal.keyInk, Offset(c.x - s / 2f, c.y - s / 2f), Size(s, s))
}

private val KEY_BAR_HEIGHT: Dp = 84.dp
