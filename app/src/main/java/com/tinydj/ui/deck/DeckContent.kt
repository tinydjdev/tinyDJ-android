package com.tinydj.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinydj.ui.theme.Metal
import com.tinydj.ui.theme.brushedVertical
import com.tinydj.ui.theme.BackdropColor

/**
 * The faithful device face — the integration surface for every owned piece.
 *
 * Layout follows BUILD SPEC PART A: the body is a portrait plate with unit cell W=1.00,
 * H≈1.35. Every element is placed by the normalized (x, y) fractions from the A.1 z-order
 * table (x∈[0,1] across the body width, y∈[0,1.35] down its height), so the proportions and
 * placement match the reference device rather than a re-flowed modern layout.
 *
 * Wordmark + status dots top-left; OLED top-right with the orange "M" capsule beneath it; the
 * big dominant reel disc fills the centre; the left-edge rocker + mode capsule sit on the left
 * wall; the right-side UP/DOWN user buttons (+ memo) on the right wall; the bottom band carries
 * RECORD/PLAY/STOP, the +/- peanut keys, the VU bars and the protruding knurled crown.
 *
 * KEEP THIS SIGNATURE — the Robolectric render test rasterizes `DeckContent(state, actions)`.
 */
@Composable
fun DeckContent(state: DeckUiState, actions: DeckActions, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(BackdropColor)
            .systemBarsPadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The device is a portrait plate of width W and height PLATE_H·W, with the knurled
            // crown overhanging its bottom edge. We size W to the larger constraint (usually the
            // screen width, minus a margin so the rounded chassis edges and the backdrop show),
            // then center the whole object — plate + overhang — in the available space.
            val hMargin = 20.dp
            val plateUnits = 1.32f                  // plate height in body-width units
            val deviceUnits = 1.45f                 // plate + crown overhang (for centering)
            val byWidth: Dp = maxWidth - hMargin * 2
            val byHeight: Dp = (maxHeight - 24.dp * 2) / deviceUnits
            val w: Dp = if (byWidth < byHeight) byWidth else byHeight

            // 1 normalized unit == the full body width; y runs 0..plateUnits down the plate.
            fun fx(nx: Float): Dp = w * nx
            fun fy(ny: Float): Dp = w * ny
            fun fw(nw: Float): Dp = w * nw

            val plateH: Dp = w * plateUnits
            val cornerR: Dp = w * 0.025f
            val originX: Dp = (maxWidth - w) / 2
            val topPad: Dp = ((maxHeight - w * deviceUnits) / 2f).coerceAtLeast(0.dp)

            // The device origin (plate top-left).
            Box(Modifier.fillMaxSize().offset(x = originX, y = topPad)) {

            // -- 0. The chassis plate: flat beige paper with a thin dark outline. ----
            val plateShape = RoundedCornerShape(cornerR)
            Box(
                Modifier
                    .size(width = w, height = plateH)
                    .clip(plateShape)
                    .background(Metal.body)
                    .border(
                        width = 1.dp,
                        color = Metal.edgeHi,
                        shape = plateShape,
                    ),
            )

            // -- 2. Reel disc Ø0.83·W @ (0.50,0.55): the dominant centre element. ----
            FacePiece(cx = fx(0.50f), cy = fy(0.55f), size = fw(0.83f)) {
                ReelDisc(
                    rotationDeg = (state.positionMs.toFloat() / 1800f) * 360f,
                    spinning = state.isPlaying,
                    motorEnabled = state.motor != MotorMode.OFF,
                    onRotate = actions.onReelRotate,
                    onHoldStart = actions.onReelHoldStart,
                    onHoldEnd = actions.onReelHoldEnd,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            FacePiece(
                cx = fx(0.14f),
                cy = fy(-0.09f),
                width = fw(0.12f),
                height = fw(0.18f),
            ) {
                PowerSwitch(
                    powered = state.powered,
                    onPowerToggle = actions.onPowerToggle,
                    width = fw(0.12f),
                    height = fw(0.18f),
                )
            }

            // -- 4. Wordmark `TP-7` (etched) top-left. ------------------            
            Box(Modifier.offset(x = fx(0.075f), y = fy(0.05f))) {
                val displayName = state.deviceName.trim()
                val wordmarkText = if (displayName.equals("TINYDJ", ignoreCase = true)) "tinyDJ" else displayName.ifBlank { "tinyDJ" }
                Text(
                    wordmarkText,
                    color = Metal.wordmark,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                )
            }

            // -- 5. Status LED dot, top centre-left. --------------------------------------
            FacePiece(cx = fx(0.35f), cy = fy(0.075f), size = fw(0.022f)) {
                StatusLed(state = state, modifier = Modifier.fillMaxSize())
            }

            // -- 6. Internal-mic ring (open circle) just right of the LED. -----------------
            val isMemoRecording = state.memoArmed
            val micRingBorderColor: Color
            val micRingBgColor: Color

            if (isMemoRecording) {
                val transition = rememberInfiniteTransition(label = "memoFlash")
                val flashAlpha by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )
                micRingBorderColor = lerp(Metal.seam, Color.Red, flashAlpha)
                micRingBgColor = lerp(Metal.body, Color.Red, flashAlpha)
            } else {
                micRingBorderColor = Metal.seam
                micRingBgColor = Metal.body
            }

            FacePiece(cx = fx(0.46f), cy = fy(0.075f), size = fw(0.024f)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(micRingBorderColor),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(1.5.dp)
                            .clip(CircleShape)
                            .background(micRingBgColor),
                    )
                }
            }

            // -- 7. OLED panel, top-right. Sized to exactly 2:1 aspect ratio so the pixel grid fills it.
            FacePiece(
                cx = fx(0.835f),
                cy = fy(0.11f),
                width = fw(0.24f),
                height = fw(0.12f),
            ) {
                MiniLcd(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { actions.onOpenLibrary() }
                )
            }

            // -- 9. Left rocker lever: side-mounted, protruding to the left. ---------------
            val rockerW = fw(0.05f)
            FacePiece(
                cx = 1.dp - rockerW * 0.375f,
                cy = fy(0.45f),
                width = rockerW,
                height = fy(0.44f),
            ) {
                SideRocker(
                    onScanStart = actions.onScanStart,
                    onScanStop = actions.onScanStop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Rocker markings on the face plate, inboard of the side edge.
            FacePiece(
                cx = fx(0.045f),
                cy = fy(0.45f),
                width = fw(0.06f),
                height = fy(0.44f),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = size.width * 0.25f
                    // FF ▲▲ near top
                    val ffY = size.height * 0.20f
                    triangle(size.width / 2, ffY - s * 0.55f, s, up = true)
                    triangle(size.width / 2, ffY + s * 0.55f, s, up = true)
                    // REW R▼ near bottom (rotated 90 degrees CCW)
                    val rewY = size.height * 0.80f
                    val cx = size.width / 2
                    triangle(cx, rewY - s * 0.55f, s * 0.9f, up = false)
                    rotate(degrees = -90f, pivot = Offset(cx, rewY + s * 0.65f)) {
                        drawLetterR(cx, rewY + s * 0.65f, s * 1.1f)
                    }
                }
            }

            // -- A.2 LEFT mode capsule: side-mounted, protruding mode button. --------------
            val modeW = fw(0.025f)
            FacePiece(
                cx = 1.dp - modeW / 2,
                cy = fy(0.82f),
                width = modeW,
                height = fy(0.28f),
            ) {
                ModeButton(
                    onTap = actions.onModeTap,
                    onHold = actions.onModeHold,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // -- 8. Orange M cosmetic label on the face plate. -----------------------------
            FacePiece(cx = fx(0.915f), cy = fy(0.33f), size = fw(0.045f)) {
                OrangeMLabel(Modifier.fillMaxSize())
            }

            // Physical Memo button: side-mounted, protruding to the right as a silver key.
            val memoW = fw(0.025f)
            FacePiece(
                cx = w - 1.dp + memoW / 2,
                cy = fy(0.33f),
                width = memoW,
                height = fy(0.16f),
            ) {
                MemoButton(
                    armed = state.memoArmed,
                    onTap = actions.onMemo,
                    onHold = actions.onMemoHold,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Physical User buttons (Up/Down): side-mounted, protruding to the right.
            val userBtnW = fw(0.025f)
            FacePiece(
                cx = w - 1.dp + userBtnW / 2,
                cy = fy(0.62f),
                width = userBtnW,
                height = fy(0.36f),
            ) {
                UserButtons(
                    onUp = actions.onUserUp,
                    onUpHold = actions.onUserUpHold,
                    onDown = actions.onUserDown,
                    onDownHold = actions.onUserDownHold,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // -- 11. +/- peanut keys, diagonal, in the disc's lower-right shoulder. --------
            FacePiece(
                cx = fx(0.815f),
                cy = fy(0.875f),
                width = fw(0.21f),
                height = fw(0.21f),
            ) {
                SkipButtons(
                    onPrev = actions.onPrev,
                    onNext = actions.onNext,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // -- Symmetrical A/B loop buttons on the disc's lower-left shoulder (Advanced mode only) --------
            if (state.playerStyle == PlayerStyle.ADVANCED) {
                FacePiece(
                    cx = fx(0.185f),
                    cy = fy(0.875f),
                    width = fw(0.21f),
                    height = fw(0.21f),
                ) {
                    LoopButtons(
                        onTapA = actions.onTapA,
                        onHoldA = actions.onHoldA,
                        onTapB = actions.onTapB,
                        onPressA = actions.onPressA,
                        onCancelVibrate = actions.onCancelVibrate,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // -- 12. The two vertical level meters: aligned vertically at bottom-right. ----
            FacePiece(
                cx = fx(0.82f),
                cy = fy(1.16f),
                width = fw(0.14f),
                height = fy(0.32f),
            ) {
                VuMeters(
                    left = state.vuLeft,
                    right = state.vuRight,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // -- 10. Transport bar: RECORD / PLAY / STOP, flush to bottom and left. --------
            FacePiece(
                cx = fx(0.33f),
                cy = fy(1.16f),
                width = fw(0.66f),
                height = fy(0.32f),
            ) {
                TransportKeys(
                    isPlaying = state.isPlaying,
                    recArmed = state.recArmed,
                    onRecord = actions.onRecord,
                    onRecordHold = actions.onRecordHold,
                    onPlay = actions.onPlay,
                    onStop = actions.onStop,
                    onStopHold = actions.onStopHold,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Mounting collar connecting volume crown directly to player body bottom edge
            FacePiece(
                cx = fx(0.82f),
                cy = fy(1.3275f),
                width = fw(0.14f),
                height = fy(0.015f),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Metal.body)
                        .border(1.dp, Metal.seam),
                )
            }

            // -- 13. Knurled volume crown: aligned under collar, protruding down. ----------
            FacePiece(
                cx = fx(0.82f),
                cy = fy(1.385f),
                width = fw(0.14f),
                height = fw(0.10f),
            ) {
                RotaryKnob(
                    volume = state.volume,
                    onVolumeChange = actions.onVolumeChange,
                    width = fw(0.14f),
                    height = fw(0.10f),
                )
            }
            } // end vertical-centering wrapper
        }
    }
}

/**
 * Places [content] centred on body-normalized point ([cx], [cy]) at the given [size]
 * (square) — a small helper so the call sites above read as the A.1 z-order coordinate table.
 */
@Composable
private fun PlaceCentered(
    cx: Dp,
    cy: Dp,
    width: Dp,
    height: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .offset(x = cx - width / 2, y = cy - height / 2)
            .size(width = width, height = height),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Square overload of [PlaceCentered]. */
@Composable
private fun FacePiece(cx: Dp, cy: Dp, size: Dp, content: @Composable () -> Unit) =
    PlaceCentered(cx, cy, size, size, content)

/** Rectangular face placement helper. */
@Composable
private fun FacePiece(cx: Dp, cy: Dp, width: Dp, height: Dp, content: @Composable () -> Unit) =
    PlaceCentered(cx, cy, width, height, content)

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

private fun DrawScope.leftTriangle(cx: Float, cy: Float, s: Float) {
    val hw = s * 0.5f
    val hh = s * 0.5f
    val p = Path().apply {
        moveTo(cx - hw, cy)
        lineTo(cx + hw, cy - hh)
        lineTo(cx + hw, cy + hh)
        close()
    }
    drawPath(p, Metal.microText)
}

private fun DrawScope.drawLetterR(x: Float, y: Float, s: Float) {
    val sw = (s * 0.16f).coerceAtLeast(1f)
    val left = x - s * 0.32f
    val right = x + s * 0.30f
    val top = y - s * 0.5f
    val bot = y + s * 0.5f
    val mid = y
    val col = Metal.microText

    drawLine(col, Offset(left, top), Offset(left, bot), strokeWidth = sw, cap = StrokeCap.Round)
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
    drawLine(col, Offset(left + s * 0.10f, mid), Offset(right, bot), strokeWidth = sw, cap = StrokeCap.Round)
}

private fun DrawScope.fileInfoGlyph() {
    val w = size.width
    val h = size.height
    drawRect(
        color = Metal.seam,
        topLeft = Offset(w * 0.1f, h * 0.1f),
        size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.8f),
        style = Stroke(width = h * 0.12f),
    )
    drawRect(
        color = Metal.seam,
        topLeft = Offset(w * 0.38f, h * 0.38f),
        size = androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.24f),
    )
}
