package com.tinydj.ui.deck

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode

/**
 * The non-menu firmware screens, each rendered as a pixel-faithful dot-matrix view through the
 * [Oled] primitive in `Oled.kt`. Everything here is "set pixel (x, y)" on the 64x48 logical grid —
 * two bitmap fonts (SMALL 5x7, BIG 7x10), explicit 1-bit icon maps and the signature inverted
 * white rounded boxes with glyphs knocked out in black. NO anti-aliasing, NO glow.
 *
 * DE-BRAND: the only wordmark anywhere on these screens is `tinydj` / `TINYDJ` / `TJ`. The start-up
 * title and the UPGRADE header render the de-branded wordmark in place of the reference model name.
 *
 * The single entry point the OLED host (MiniLcd) calls is [OledScreen]; it switches on
 * [DeckUiState.oledView] (and the menu flags, delegating those to SystemMenu.kt) and paints the
 * matching screen. Each individual screen is also exposed for previews/tests.
 *
 * Layout reference (64 cols x 48 rows):
 *   - BIG value line baseline near y = 14 (top ~60% of panel), SMALL status line near the bottom.
 *   - inverted token boxes hug the right edge.
 */

// =====================================================================================
//  Dispatcher
// =====================================================================================

/**
 * The one composable the OLED host hands the whole [DeckUiState]. It resolves the current
 * [OledView] (with the system-menu overlays taking precedence) and draws exactly one screen,
 * pixel-for-pixel, into an [Oled] block.
 */
@Composable
fun OledScreen(state: DeckUiState, modifier: Modifier = Modifier) {
    // Powered-off panel is dark (the host normally hides it, but be safe and paint nothing lit).
    if (!state.powered && !state.usbConnected) {
        Oled(modifier) { /* dark well only */ }
        return
    }

    // System menu owns the panel when open (delegated to SystemMenu.kt).
    if (state.menuOpen || state.settingOpen) {
        OledMenu(state, modifier)
        return
    }

    when (state.oledView) {
        OledView.TITLE -> OledTitle(state, modifier)
        OledView.PLAYBACK -> OledPlayback(state, modifier)
        OledView.NO_FILE -> OledNoFile(modifier)
        OledView.TIME_STATUS -> OledTimeStatus(state, modifier)
        OledView.BATTERY -> OledBattery(state, modifier)
        OledView.MTP -> OledMtp(state, modifier)
        OledView.UPGRADE -> OledUpgrade(state, modifier)
        OledView.VARISPEED -> OledVarispeed(state, modifier)
        OledView.VOLUME_WEDGE -> OledVolumeWedge(state, modifier)
        OledView.FILE_INFO -> OledFileInfo(state, modifier)
        OledView.MODE_BADGE -> OledModeBadge(state.appMode, modifier)
        OledView.GAIN -> OledGain(state, modifier)
        OledView.EDIT_PROP -> OledEditProp(state, modifier)
        OledView.DELETE_CONFIRM -> OledDelete(state, modifier)
        OledView.MENU_LIST, OledView.SETTING_EDIT -> OledMenu(state, modifier)
    }
}

// =====================================================================================
//  Geometry helpers (centering on the 64x48 grid)
// =====================================================================================

private const val COLS = OLED_COLS   // 64
private const val ROWS = OLED_ROWS   // 48

/** Left x that centers a [Font] string [s] horizontally. */
private fun PixelCanvas.centerX(s: String, font: Font): Int =
    ((COLS - textWidth(s, font)) / 2).coerceAtLeast(0)

/** Draw [s] horizontally centered at row [y]. Returns the width drawn. */
private fun PixelCanvas.textCentered(y: Int, s: String, font: Font, lit: Boolean = true): Int =
    text(centerX(s, font), y, s, font, lit)

// =====================================================================================
//  TITLE — start-up (manual p.12)
// =====================================================================================

/**
 * Power-on splash: BIG `tinydj` centered on the panel. DE-BRAND — this is where the reference shows
 * its model wordmark; we show ours. Held ~1.2s by the VM, then the last-open playback screen.
 */
@Composable
fun OledTitle(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        // BIG letters are 10 rows tall → vertically center at (32-10)/2 = 11.
        val name = state.deviceName.trim().ifBlank { "TINYDJ" }
        textCentered(11, name.uppercase(), Font.BIG)
    }
}

// =====================================================================================
//  PLAYBACK (manual p.14)
// =====================================================================================

/**
 * The main playback screen: BIG dotted timecode `H.MM.SS` on the value line (left aligned, upper
 * ~60%); a SMALL bottom-left label (track name / TODAY / JAN,1); and the device's inverted
 * 2-digit file box bottom-right (`01`). A tiny direction caret sits after the timecode when
 * playing in reverse.
 *
 * If the track name is too long to fit in the available space, it scrolls slowly from right to left.
 */
@Composable
fun OledPlayback(state: DeckUiState, modifier: Modifier = Modifier) {
    val label = if (state.hasTrack) state.dateLabel.uppercase() else "TODAY"
    val hasBox = state.trackNumber > 0
    val boxWidth = if (hasBox) {
        val box = "%02d".format(state.trackNumber.coerceIn(0, 99))
        box.length * 6 - 1 + 4
    } else {
        0
    }
    val labelWidth = label.length * 6 - 1
    val labelAreaWidth = if (hasBox) COLS - boxWidth - 3 else COLS - 4
    val maxScroll = (labelWidth - labelAreaWidth).coerceAtLeast(0)

    val progress = if (maxScroll > 0) {
        val tStart = 1500
        val tScroll = maxScroll * 100
        val tEnd = 1500
        val tReset = 500
        val totalDuration = tStart + tScroll + tEnd + tReset

        val transition = rememberInfiniteTransition(label = "trackNameScroll")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = totalDuration, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "progress"
        ).value
    } else {
        0f
    }

    Oled(modifier) {
        // --- value line: BIG timecode ---
        val tc = timecode(state.positionMs)
        text(2, 6, tc, Font.BIG)

        // reverse direction caret after the timecode (small)
        if (state.reversed) {
            val cx = 2 + textWidth(tc, Font.BIG) + 3
            stamp3(cx, 9, listOf("..#", ".##", "###", ".##", "..#"))
        }

        // --- bottom-left SMALL label ---
        val scrollOffset = if (maxScroll > 0) {
            val tStart = 1500f
            val tScroll = maxScroll * 100f
            val tEnd = 1500f
            val tReset = 500f
            val total = tStart + tScroll + tEnd + tReset
            val ms = progress * total
            when {
                ms < tStart -> 0f
                ms < tStart + tScroll -> {
                    val ratio = (ms - tStart) / tScroll
                    maxScroll * ratio
                }
                ms < tStart + tScroll + tEnd -> maxScroll.toFloat()
                else -> {
                    val ratio = (ms - tStart - tScroll - tEnd) / tReset
                    maxScroll * (1f - ratio)
                }
            }
        } else {
            0f
        }

        setClipX(2, if (hasBox) COLS - boxWidth - 1 else COLS - 1)
        text(2 - scrollOffset.toInt(), ROWS - 9, label, Font.SMALL)
        clearClipX()

        // --- bottom-right inverted 2-digit file box ---
        if (hasBox) {
            val box = "%02d".format(state.trackNumber.coerceIn(0, 99))
            val bw = textWidth(box, Font.SMALL) + 4    // padX 2 each side
            boxedText(COLS - bw - 1, ROWS - 11, box, Font.SMALL)
        }
    }
}

/** Bottom-left label: the track name when present, else the date/relative label. */
private fun playbackLabel(state: DeckUiState): String {
    val raw = if (state.hasTrack) state.dateLabel else "TODAY"
    return raw.uppercase().take(10)
}

// =====================================================================================
//  NO FILE (empty MEMO/REC mode, or empty library)
// =====================================================================================

/** Reel icon top-left + SMALL `NO FILE`. Shown when the active mode list is empty. */
@Composable
fun OledNoFile(modifier: Modifier = Modifier) {
    Oled(modifier) {
        reel(6, 4)                       // 22x11 twin-hub cassette/reel
        text(6, 20, "NO FILE", Font.SMALL)
    }
}

// =====================================================================================
//  TIME / DATE status (manual p.18)
// =====================================================================================

/**
 * The clock/date status: SMALL weekday top-left (`TUE`), inverted year box top-right (`1980`), and
 * BIG month+day (`JAN01`) on the value line.
 */
@Composable
fun OledTimeStatus(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        // top-left weekday
        text(2, 2, state.weekday.uppercase().take(3), Font.SMALL)
        // top-right inverted year box
        val yr = state.year.toString()
        val bw = textWidth(yr, Font.SMALL) + 4
        boxedText(COLS - bw - 1, 1, yr, Font.SMALL)
        // BIG month+day on the value line
        val md = monthDay(state.date)
        text(centerX(md, Font.BIG), 18, md, Font.BIG)
    }
}

// =====================================================================================
//  BATTERY (manual p.40)
// =====================================================================================

/** Battery glyph centered (outline + level fill + terminal nub; charging bolt) + SMALL `100%`. */
@Composable
fun OledBattery(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        // battery body is 16 wide (+2 nub) → center the 18px glyph.
        val bx = (COLS - 18) / 2
        battery(bx, 6, state.batteryPct, state.charging)
        val pct = "${state.batteryPct.coerceIn(0, 100)}%"
        textCentered(ROWS - 12, pct, Font.SMALL)
    }
}

// =====================================================================================
//  MTP — power off + USB
// =====================================================================================

/** Battery + `100%` (bolt above) on the left | USB-plug icon + `MTP` on the right. */
@Composable
fun OledMtp(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        // left: battery + percent
        battery(4, 6, state.batteryPct, charging = true)
        text(4, 20, "${state.batteryPct.coerceIn(0, 100)}%", Font.SMALL)
        // right: USB plug + MTP label
        usbPlug(40, 6)
        text(38, 20, "MTP", Font.SMALL)
    }
}

// =====================================================================================
//  UPGRADE / VERSION (manual p.33)
// =====================================================================================

/**
 * The firmware version readout: SMALL header `TINYDJ UPGRADE` (wordmark left, UPGRADE right;
 * DE-BRAND) over BIG dotted version `0.1.0`.
 */
@Composable
fun OledUpgrade(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        val name = state.deviceName.trim().ifBlank { "TINYDJ" }
        text(2, 2, name.uppercase(), Font.SMALL)
        val up = "UPGRADE"
        text(COLS - textWidth(up, Font.SMALL) - 1, 2, up, Font.SMALL)
        val ver = state.firmwareVersion
        text(centerX(ver, Font.BIG), 18, ver, Font.BIG)
    }
}

// =====================================================================================
//  VARISPEED RULER (manual p.32)
// =====================================================================================

/**
 * The varispeed editor: BIG signed value centered above (`0` default, `-50` min, `+200` max) and a
 * horizontal tick ruler below with a deeper center notch marking the 0 detent.
 */
@Composable
fun OledVarispeed(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        val v = state.varispeed.coerceIn(-50, 200)
        val label = if (v > 0) "+$v" else v.toString()
        text(centerX(label, Font.BIG), 6, label, Font.BIG)
        // ruler baseline near the bottom; ticks rise above it.
        val rw = 56
        ruler((COLS - rw) / 2, ROWS - 8, rw, majorEvery = 5)
    }
}

// =====================================================================================
//  VOLUME WEDGE
// =====================================================================================

/** A filled right-growing triangle ramp — no numbers — brief overlay while the crown turns. */
@Composable
fun OledVolumeWedge(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        val w = 52
        val h = 18
        wedge((COLS - w) / 2, (ROWS - h) / 2, w, h, state.volume)
    }
}

// =====================================================================================
//  FILE INFO (hold DOWN)
// =====================================================================================

/**
 * The file-info card: a top inverted box pairing the sample rate with a stereo/mono glyph and a
 * far-right mode badge, a SMALL middle length (`00:04`), and the bottom file id (`#1`).
 */
@Composable
fun OledFileInfo(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        // --- top inverted box: sample rate + channel glyph ---
        val khz = "${(state.fileSampleRate / 1000).coerceAtLeast(1)}"
        val srLabel = "$khz K"               // e.g. "48 K" (kHz mark is not brand)
        val boxW = textWidth(srLabel, Font.SMALL) + 4
        boxedText(2, 2, srLabel, Font.SMALL)
        // channel glyph just right of the box: stereo = two hubs, mono = one
        val gx = 2 + boxW + 3
        channelGlyph(gx, 3, stereo = true)

        // far-right mode badge (small icon tile)
        modeBadgeSmall(COLS - 13, 1, state.appMode)

        // --- middle: SMALL track length ---
        text(2, 13, "LEN ${timecodeShort(state.durationMs)}", Font.SMALL)

        // --- bottom: file id ---
        val id = "#${state.trackNumber.coerceAtLeast(0)}"
        text(2, ROWS - 9, id, Font.SMALL)
    }
}

// =====================================================================================
//  MODE badges (full-screen)
// =====================================================================================

/** Full-screen mode glyph: MEMO=`M` tile, REC=twin-reel, LIBRARY=ear. */
@Composable
fun OledModeBadge(mode: AppMode, modifier: Modifier = Modifier) {
    Oled(modifier) {
        when (mode) {
            AppMode.MEMO -> {
                // big M centered
                text(centerX("M", Font.BIG), 6, "M", Font.BIG)
                textCentered(ROWS - 11, "MEMO", Font.SMALL)
            }
            AppMode.REC -> {
                reel(21, 6)                   // 22x11 twin-reel centered-ish
                textCentered(ROWS - 11, "REC", Font.SMALL)
            }
            AppMode.LIBRARY -> {
                modeIcon(26, 4, ModeIcon.EAR)
                textCentered(ROWS - 11, "LIB", Font.SMALL)
            }
        }
    }
}

// =====================================================================================
//  GAIN prop (inert)
// =====================================================================================

/** Inert gain prop: BIG `0dB`..`+42dB`. Drawn faithfully, controls nothing. */
@Composable
fun OledGain(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        val g = state.gainDb.coerceIn(0, 42)
        val label = if (g > 0) "+$g" else "$g"
        // BIG number centered on the value line, SMALL "DB" suffix under it.
        text(centerX(label, Font.BIG), 6, label, Font.BIG)
        textCentered(ROWS - 11, "DB", Font.SMALL)
    }
}

// =====================================================================================
//  EDIT prop (inert)
// =====================================================================================

/**
 * Inert EDIT prop: timecode top, `EDIT` bottom-left, `3 TR` bottom-right. When recArmed is cleared
 * after toggling off, the firmware briefly shows a full-screen `EDIT OFF` — surfaced here when
 * there is no track to time against.
 */
@Composable
fun OledEditProp(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        if (!state.recArmed) {
            textCentered(12, "EDIT OFF", Font.SMALL)
            return@Oled
        }
        text(2, 4, timecode(state.positionMs), Font.BIG)
        text(2, ROWS - 9, "EDIT", Font.SMALL)
        val tr = "3 TR"
        text(COLS - textWidth(tr, Font.SMALL) - 1, ROWS - 9, tr, Font.SMALL)
    }
}

// =====================================================================================
//  DELETE confirm (overlay)
// =====================================================================================

/**
 * The delete prompt: `X DEL?` on the value line with inverted `YES` / plain `NO` below — the
 * selected option is the boxed (inverted) one. The reel toggles selection; PLUS confirms.
 */
@Composable
fun OledDelete(state: DeckUiState, modifier: Modifier = Modifier) {
    Oled(modifier) {
        // cross glyph + DEL?
        crossGlyph(4, 2)
        text(12, 3, "DEL?", Font.SMALL)

        // YES / NO row near the bottom — selected one is the inverted box.
        val yesX = 6
        val noX = 34
        val yy = ROWS - 11
        if (state.deleteSelectYes) {
            boxedText(yesX, yy, "YES", Font.SMALL)
            text(noX, yy + 1, "NO", Font.SMALL)
        } else {
            text(yesX, yy + 1, "YES", Font.SMALL)
            boxedText(noX, yy, "NO", Font.SMALL)
        }
    }
}

// =====================================================================================
//  Small drawing helpers local to these screens
// =====================================================================================

/** Stamp an explicit `#`/`.` pixel map (used for the tiny direction caret & inline glyphs). */
private fun PixelCanvas.stamp3(x: Int, y: Int, rows: List<String>) {
    for (r in rows.indices) {
        val row = rows[r]
        for (c in row.indices) if (row[c] == '#') set(x + c, y + r, true)
    }
}

/** A small `X` (delete) glyph, 7x7. */
private fun PixelCanvas.crossGlyph(x: Int, y: Int) {
    stamp3(
        x, y,
        listOf(
            "#.....#",
            ".#...#.",
            "..#.#..",
            "...#...",
            "..#.#..",
            ".#...#.",
            "#.....#",
        ),
    )
}

/** Stereo (two hubs) / mono (one hub) mini channel glyph for the file-info box. */
private fun PixelCanvas.channelGlyph(x: Int, y: Int, stereo: Boolean) {
    fun hub(hx: Int) = stamp3(
        hx, y,
        listOf(
            ".###.",
            "#...#",
            "#.#.#",
            "#...#",
            ".###.",
        ),
    )
    hub(x)
    if (stereo) hub(x + 6)
}

/** A compact mode badge (~10px) for the file-info top-right corner. */
private fun PixelCanvas.modeBadgeSmall(x: Int, y: Int, mode: AppMode) {
    when (mode) {
        AppMode.MEMO -> text(x, y, "M", Font.SMALL)
        AppMode.REC -> reel(x - 8, y, rails = false)   // two mini hubs, no rails
        AppMode.LIBRARY -> text(x, y, "L", Font.SMALL)
    }
}

// =====================================================================================
//  Value formatting
// =====================================================================================

/** Dotted timecode like the device: `H.MM.SS`. */
private fun timecode(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d.%02d.%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** Short colon timecode `MM:SS` for the file-info length line. */
private fun timecodeShort(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format((s % 3600) / 60, s % 60)
}

/**
 * Derive the BIG month+day token (`JAN01`) from the state's display date. The VM stores `date`
 * either as `dd/mm/yy` or already as `JAN01`; normalize to a `MMMDD` form for the value line.
 */
private fun monthDay(date: String): String {
    // already month+day form? (contains a letter)
    if (date.any { it.isLetter() }) return date.uppercase().take(5)
    // dd/mm/yy → MMMdd
    val parts = date.split('/', '-', '.')
    if (parts.size >= 2) {
        val dd = parts[0].toIntOrNull()?.coerceIn(1, 31) ?: 1
        val mm = parts[1].toIntOrNull()?.coerceIn(1, 12) ?: 1
        val months = arrayOf(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
        )
        return "%s%02d".format(months[mm - 1], dd)
    }
    return date.uppercase().take(5)
}
