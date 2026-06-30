package com.tinydj.ui.deck

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinydj.ui.theme.Metal
import com.tinydj.ui.theme.keyFill

/**
 * The smaller chassis controls for the device face, per BUILD SPEC PART A and manual
 * pages 6 (face), 10 (right side) and 18 (transport / peanut keys):
 *
 *  - [ModeButton]   — the low-left vertical mode capsule (tap = mode / varispeed, hold = menu).
 *  - [MemoButton]   — the top-right orange "M" key (faithful, inert capture prop).
 *  - [SkipButtons]  — the `+`/`−` peanut keys tucked into the reel notch.
 *  - [VuMeters]     — two thin vertical LED bars (animate only while active so the
 *                     frame clock can idle when paused).
 *  - [UserButtons]  — the RIGHT-SIDE stacked round UP / DOWN user-button pair. These were
 *                     wrongly omitted by the previous build; they MUST be present and call
 *                     onUp / onUpHold / onDown / onDownHold.
 *  - [OrangeMLabel] — the small on-face orange "M" capsule label.
 *  - [StatusLed]    — the tiny status pinhole (red = low batt, white = full charge).
 *
 * These widgets emit RAW press / hold / movement events only. The per-unit value detents
 * (one crisp tick per discrete unit crossed) are owned entirely by [DeckViewModel] — the
 * controls never count notches themselves.
 */

// ---------------------------------------------------------------------------
// ModeButton — vertical mode capsule (low-left edge). Tap + long-press.
// ---------------------------------------------------------------------------

/**
 * The mode capsule (BUILD SPEC A.2 left edge): a vertical pill. A short [onTap] cycles the
 * app mode while stopped / opens varispeed while playing; a long-press [onHold] opens the
 * system menu. Tri-modal routing lives in the ViewModel — this only reports tap vs. hold.
 */
@Composable
fun ModeButton(
    onTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 0.dp, bottomEnd = 0.dp)
    Box(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(Metal.key)
            .border(1.dp, Metal.seam, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onHold() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .border(1.dp, Metal.keyInk, CircleShape)
        )
    }
}

// ---------------------------------------------------------------------------
// MemoButton — top-right orange "M" key (inert capture prop).
// ---------------------------------------------------------------------------

/**
 * The orange memo key (BUILD SPEC A.1 #8 / A.2 right edge). A faithful capture prop on this
 * playback-only clone: [onTap] / [onHold] animate and tick but write no audio. [armed]
 * brightens the key while the prop "records".
 */
@Composable
fun MemoButton(
    armed: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 4.dp, bottomEnd = 4.dp)
    Box(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(Metal.key)
            .border(1.dp, Metal.seam, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onHold() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (armed) Metal.keyInk else Color.Transparent)
                .border(1.dp, Metal.keyInk, CircleShape)
        )
    }
}

// ---------------------------------------------------------------------------
// SkipButtons — the +/- peanut keys (figure-8 housing).
// ---------------------------------------------------------------------------

/**
 * The `−` / `+` peanut keys (BUILD SPEC A.1 #11). Each discrete press fires its callback
 * once; the ViewModel emits exactly one value tick per press (MINUS = prev, PLUS = next).
 *
 * They sit diagonally — `+` upper-right, `−` lower-left — exactly as on the device, so the pair
 * nestles into the disc's lower-right shoulder instead of reading as a flat row.
 */
@Composable
fun SkipButtons(onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        // Draw the peanut outline connecting them
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val padPx = 3.dp.toPx()
            val keyRPx = 12.dp.toPx()
            val start = Offset(padPx + keyRPx, h - padPx - keyRPx)
            val end = Offset(w - padPx - keyRPx, padPx + keyRPx)
            val len = (end - start).getDistance()
            val angleRad = Math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
            val angleDeg = Math.toDegrees(angleRad).toFloat()
            val center = Offset(w / 2f, h / 2f)
            val pillR = 17.dp.toPx()

            rotate(angleDeg, center) {
                // Draw filled pill
                drawRoundRect(
                    color = Metal.key,
                    topLeft = Offset(center.x - len / 2f - pillR, center.y - pillR),
                    size = Size(len + pillR * 2f, pillR * 2f),
                    cornerRadius = CornerRadius(pillR, pillR)
                )
                // Draw outline
                drawRoundRect(
                    color = Metal.edgeHi,
                    topLeft = Offset(center.x - len / 2f - pillR, center.y - pillR),
                    size = Size(len + pillR * 2f, pillR * 2f),
                    cornerRadius = CornerRadius(pillR, pillR),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        // `+` upper-right with padding
        Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp)) {
            RoundKey(onClick = onNext) { plusGlyph() }
        }
        // `−` lower-left with padding
        Box(Modifier.align(Alignment.BottomStart).padding(bottom = 3.dp, start = 3.dp)) {
            RoundKey(onClick = onPrev) { minusGlyph() }
        }
    }
}

@Composable
private fun RoundKey(onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Metal.key)
            .border(1.dp, Metal.seam, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val ok = tryAwaitRelease()
                    if (ok) onClick()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(11.dp)) { glyph() }
    }
}

private fun DrawScope.plusGlyph() {
    val w = size.width; val h = size.height
    drawLine(Metal.ink, Offset(w / 2, h * 0.1f), Offset(w / 2, h * 0.9f), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
    drawLine(Metal.ink, Offset(w * 0.1f, h / 2), Offset(w * 0.9f, h / 2), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.minusGlyph() {
    val w = size.width; val h = size.height
    drawLine(Metal.ink, Offset(w * 0.1f, h / 2), Offset(w * 0.9f, h / 2), strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
}

// ---------------------------------------------------------------------------
// VuMeters — two thin vertical tracks with moving sliders.
// ---------------------------------------------------------------------------

@Composable
fun VuMeters(left: Float, right: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawVuMeters(left, right)
    }
}

private fun DrawScope.drawVuMeters(leftLevel: Float, rightLevel: Float) {
    val top = size.height * 0.05f
    val bottom = size.height * 0.95f
    val lx = size.width * 0.35f
    val rx = size.width * 0.65f
    val height = bottom - top
    
    // Slim square-ovals width
    val slotW = 5.dp.toPx()
    val cornerRadius = CornerRadius(slotW / 2f, slotW / 2f)
    
    // Define path for clipping the filled level inside the left slot shape
    val leftPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(
                    left = lx - slotW / 2f,
                    top = top,
                    right = lx + slotW / 2f,
                    bottom = bottom
                ),
                cornerRadius = cornerRadius
            )
        )
    }
    
    // Define path for clipping the filled level inside the right slot shape
    val rightPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(
                    left = rx - slotW / 2f,
                    top = top,
                    right = rx + slotW / 2f,
                    bottom = bottom
                ),
                cornerRadius = cornerRadius
            )
        )
    }
    
    val markerY = top + height * 0.25f // 75% height threshold (peaking limit)
    
    // Draw fills (if any volume)
    if (leftLevel > 0f) {
        val levelH = height * leftLevel.coerceIn(0f, 1f)
        val fillTop = bottom - levelH
        clipPath(leftPath) {
            if (fillTop < markerY) {
                // Peaking: draw normal level below threshold, and red level above threshold
                drawRect(
                    color = Metal.vuOn,
                    topLeft = Offset(lx - slotW, markerY),
                    size = Size(slotW * 2, bottom - markerY)
                )
                drawRect(
                    color = Color(0xFFE53935), // Red LED peak
                    topLeft = Offset(lx - slotW, fillTop),
                    size = Size(slotW * 2, markerY - fillTop)
                )
            } else {
                drawRect(
                    color = Metal.vuOn,
                    topLeft = Offset(lx - slotW, fillTop),
                    size = Size(slotW * 2, levelH)
                )
            }
        }
    }
    
    if (rightLevel > 0f) {
        val levelH = height * rightLevel.coerceIn(0f, 1f)
        val fillTop = bottom - levelH
        clipPath(rightPath) {
            if (fillTop < markerY) {
                drawRect(
                    color = Metal.vuOn,
                    topLeft = Offset(rx - slotW, markerY),
                    size = Size(slotW * 2, bottom - markerY)
                )
                drawRect(
                    color = Color(0xFFE53935), // Red LED peak
                    topLeft = Offset(rx - slotW, fillTop),
                    size = Size(slotW * 2, markerY - fillTop)
                )
            } else {
                drawRect(
                    color = Metal.vuOn,
                    topLeft = Offset(rx - slotW, fillTop),
                    size = Size(slotW * 2, levelH)
                )
            }
        }
    }
    
    // Draw vertical slot outlines
    drawRoundRect(
        color = Metal.seam,
        topLeft = Offset(lx - slotW / 2f, top),
        size = Size(slotW, height),
        cornerRadius = cornerRadius,
        style = Stroke(width = 1.dp.toPx())
    )
    
    drawRoundRect(
        color = Metal.seam,
        topLeft = Offset(rx - slotW / 2f, top),
        size = Size(slotW, height),
        cornerRadius = cornerRadius,
        style = Stroke(width = 1.dp.toPx())
    )
    
    // Draw thin horizontal marker line crossing both slots
    val markerStart = lx - slotW * 1.5f
    val markerEnd = rx + slotW * 1.5f
    drawLine(
        color = Metal.seam,
        start = Offset(markerStart, markerY),
        end = Offset(markerEnd, markerY),
        strokeWidth = 1.dp.toPx()
    )
}

// ---------------------------------------------------------------------------
// UserButtons — RIGHT-SIDE stacked round UP / DOWN user-button pair.
// ---------------------------------------------------------------------------

@Composable
fun UserButtons(
    onUp: () -> Unit,
    onUpHold: () -> Unit,
    onDown: () -> Unit,
    onDownHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 4.dp, bottomEnd = 4.dp)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(Metal.key)
                .border(1.dp, Metal.seam, shape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onUp() },
                        onLongPress = { onUpHold() }
                    )
                }
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(shape)
                .background(Metal.key)
                .border(1.dp, Metal.seam, shape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDown() },
                        onLongPress = { onDownHold() }
                    )
                }
        )
    }
}

// ---------------------------------------------------------------------------
// OrangeMLabel — small on-face orange "M" capsule (BUILD SPEC A.1 #8).
// ---------------------------------------------------------------------------

/**
 * The small on-face orange "M" square label that marks the right-side memo key
 * (BUILD SPEC A.1 #8). Purely cosmetic — the only orange capsule on the face.
 */
@Composable
fun OrangeMLabel(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier
            .size(16.dp)
            .clip(shape)
            .background(Metal.accent)
            .border(1.5.dp, Metal.accent, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "M",
            color = Metal.accentInk,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// StatusLed — tiny status pinhole (red = low batt, white = full charge).
// ---------------------------------------------------------------------------

/**
 * The status LED dot (BUILD SPEC A.1 #5): a small dark pinhole that glows red on low
 * battery and white when fully charged, and sits dark/at-rest otherwise.
 */
@Composable
fun StatusLed(state: DeckUiState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ledFlash")
    val flashAlpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val color: Color = when {
        !state.powered -> Metal.ledRest
        state.recArmed -> {
            if (flashAlpha > 0.5f) Metal.ledRed else Metal.ledRest
        }
        state.charging && state.batteryPct >= 100 -> Metal.ledWhite
        state.batteryPct <= 5 -> {
            if (flashAlpha > 0.5f) Metal.ledRed else Metal.ledRest
        }
        state.batteryPct <= 15 -> Metal.ledRed
        else -> Metal.ledRest
    }
    Box(
        modifier
            .size(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}

// ---------------------------------------------------------------------------
// PowerSwitch — top-left knurled power knob. Click to toggle.
// ---------------------------------------------------------------------------

/**
 * The top-mounted knurled power knob (protruding from the top edge).
 * Tapping it toggles the power state of the device.
 */
@Composable
fun PowerSwitch(
    powered: Boolean,
    onPowerToggle: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 48.dp,
    height: Dp = 72.dp,
) {
    val poweredState = rememberUpdatedState(powered)
    
    // Animate the power transition progress from 0f (OFF/wide) to 1f (ON/thin)
    val powerProgress by animateFloatAsState(
        targetValue = if (powered) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "powerProgress"
    )

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onPowerToggle() })
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // "Turn On Here" text above the switch (visible only when OFF)
        if (powerProgress < 0.99f) {
            val textAlpha = 1f - powerProgress
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .alpha(textAlpha),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Turn",
                    color = Metal.wordmark,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 10.sp
                )
                Text(
                    text = "On",
                    color = Metal.wordmark,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 10.sp
                )
                Text(
                    text = "Here",
                    color = Metal.wordmark,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 10.sp
                )
            }
        }

        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val w = size.width
            val h = size.height

            // Both buttons are the same height (sits on the top chassis edge).
            // Leaves the top portion of the canvas (h * 0.45f) free for the guide text.
            val rectH = h * 0.55f
            
            // Width animates between wide (w * 0.85f) and thin (w * 0.25f)
            val rectW = w * (0.85f - 0.60f * powerProgress)
            val rectLeft = (w - rectW) / 2f
            val rectTop = h - rectH

            val cylinderPath = Path().apply {
                moveTo(rectLeft, h)
                lineTo(rectLeft + rectW, h)
                lineTo(rectLeft + rectW, rectTop)
                lineTo(rectLeft, rectTop)
                close()
            }

            // Fill background (completely plain)
            drawPath(path = cylinderPath, color = Metal.knurl)

            // Draw a vertical slot in the center only when OFF (fades out as it turns ON)
            val slotAlpha = 1f - powerProgress
            if (slotAlpha > 0.01f) {
                val slotX = w / 2f
                drawLine(
                    color = Metal.seam.copy(alpha = slotAlpha),
                    start = Offset(slotX, rectTop + rectH * 0.2f),
                    end = Offset(slotX, h - rectH * 0.2f),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // Draw outline around the switch rectangle
            drawPath(
                path = cylinderPath,
                color = Metal.seam,
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

// ---------------------------------------------------------------------------
// LoopButtons — the A/B loop peanut keys (figure-8 housing, mirrored).
// ---------------------------------------------------------------------------

@Composable
fun LoopButtons(
    onTapA: () -> Unit,
    onHoldA: () -> Unit,
    onTapB: () -> Unit,
    onPressA: () -> Unit,
    onCancelVibrate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        // Draw the peanut outline connecting them (mirrored from SkipButtons)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val padPx = 3.dp.toPx()
            val keyRPx = 12.dp.toPx()
            // Symmetrical diagonal: top-left to bottom-right
            val start = Offset(padPx + keyRPx, padPx + keyRPx)
            val end = Offset(w - padPx - keyRPx, h - padPx - keyRPx)
            val len = (end - start).getDistance()
            val angleRad = Math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
            val angleDeg = Math.toDegrees(angleRad).toFloat()
            val center = Offset(w / 2f, h / 2f)
            val pillR = 17.dp.toPx()

            rotate(angleDeg, center) {
                // Draw filled pill
                drawRoundRect(
                    color = Metal.key,
                    topLeft = Offset(center.x - len / 2f - pillR, center.y - pillR),
                    size = Size(len + pillR * 2f, pillR * 2f),
                    cornerRadius = CornerRadius(pillR, pillR)
                )
                // Draw outline
                drawRoundRect(
                    color = Metal.edgeHi,
                    topLeft = Offset(center.x - len / 2f - pillR, center.y - pillR),
                    size = Size(len + pillR * 2f, pillR * 2f),
                    cornerRadius = CornerRadius(pillR, pillR),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        // `A` upper-left with padding (starts loop-in, holds to stop loop)
        Box(Modifier.align(Alignment.TopStart).padding(top = 3.dp, start = 3.dp)) {
            LoopKeyAText(
                onTap = onTapA,
                onHold = onHoldA,
                onPressStart = onPressA,
                onCancel = onCancelVibrate,
                text = "A"
            )
        }
        // `B` lower-right with padding (sets loop-out or shortens loop)
        Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 3.dp, end = 3.dp)) {
            RoundKeyText(onClick = onTapB, text = "B")
        }
    }
}

@Composable
private fun LoopKeyAText(
    onTap: () -> Unit,
    onHold: () -> Unit,
    onPressStart: () -> Unit,
    onCancel: () -> Unit,
    text: String
) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Metal.key)
            .border(1.dp, Metal.seam, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onPressStart()
                    val released = kotlinx.coroutines.withTimeoutOrNull(3000) {
                        tryAwaitRelease()
                    }
                    if (released == null) {
                        onHold()
                        tryAwaitRelease()
                    } else {
                        if (released) {
                            onTap()
                        } else {
                            onCancel()
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Metal.ink,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun RoundKeyText(onClick: () -> Unit, text: String) {
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Metal.key)
            .border(1.dp, Metal.seam, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val ok = tryAwaitRelease()
                    if (ok) onClick()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Metal.ink,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}

