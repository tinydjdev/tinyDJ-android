package com.tinydj.ui.deck

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

/**
 * The device's WORKING system menu, rendered on the monochrome OLED with the [Oled] pixel
 * primitive (BUILD SPEC B.2 / D.3 — manual pages 15–17, transcribed pixel-for-pixel).
 *
 * Two surfaces:
 *  - [SystemMenuList]  — the vertical scrolling icon list of all 17 items, the highlighted
 *                        (selected) row inverted, with a scrollbar nub on the right rail.
 *  - [SettingPage]     — the per-item value page: 16×16 tile + SMALL label + the current
 *                        value drawn in the BIG/SMALL faces; multi-field pages (TIME / DATE /
 *                        NAME) invert the active field.
 *
 * Navigation logic lives in [DeckViewModel]; this file only paints whatever the
 * [DeckUiState] cursor (menuIndex / settingOpen / settingFieldIndex) and current values say.
 *
 * DE-BRAND: the NAME tile renders `TJ` and the device name defaults to `TINYDJ`; no real
 * brand or model token appears anywhere here.
 */

// ---------------------------------------------------------------------------
//  Pure helpers (no Compose) — label / icon / value text for an item.
// ---------------------------------------------------------------------------

/** The all-caps SMALL-font label for a menu item (`JACKS`..`BATT`). */
fun menuLabel(item: MenuItem): String = item.name

/** Map the menu [MenuItem] (state enum) onto the [MenuIcon] tile bitmap (renderer enum). */
fun menuIcon(item: MenuItem): MenuIcon = when (item) {
    MenuItem.EDIT -> MenuIcon.EDIT
    MenuItem.PLAY -> MenuIcon.PLAY
    MenuItem.LEDS -> MenuIcon.LEDS
    MenuItem.VIB -> MenuIcon.VIB
    MenuItem.SPK -> MenuIcon.SPK
    MenuItem.SFX -> MenuIcon.SFX
    MenuItem.MOTOR -> MenuIcon.MOTOR
    MenuItem.MEMO -> MenuIcon.MEMO
    MenuItem.RATE -> MenuIcon.RATE
    MenuItem.DISK -> MenuIcon.DISK
    MenuItem.NAME -> MenuIcon.NAME
    MenuItem.TIME -> MenuIcon.TIME
    MenuItem.DATE -> MenuIcon.DATE
    MenuItem.VER -> MenuIcon.VER
    MenuItem.RESET -> MenuIcon.RESET
    MenuItem.BATT -> MenuIcon.BATT
}

/**
 * The current value as the firmware shows it on the item's settings page. Multi-field pages
 * (TIME / DATE / NAME) return the whole composite; the page renderer inverts the active field.
 */
fun settingValueText(item: MenuItem, state: DeckUiState): String = when (item) {
    MenuItem.EDIT -> when (state.editMode) {
        EditMode.REPLACE -> "REPLACE"
        EditMode.MIX -> "MIX"
    }
    MenuItem.PLAY -> when (state.playMode) {
        PlayMode.RESUME -> "RESUME"
        PlayMode.STOP -> "STOP"
        PlayMode.REPEAT -> "REPEAT"
    }
    MenuItem.LEDS -> when (state.leds) {
        LedBrightness.LOW -> "LOW"
        LedBrightness.MEDIUM -> "MEDIUM"
        LedBrightness.HIGH -> "HIGH"
    }
    MenuItem.VIB -> "${(state.vibrationStrength * 100).roundToInt()}%"
    MenuItem.SPK -> when (state.spk) {
        SpkMode.ON -> "ON"
        SpkMode.AUTO -> "AUTO"
    }
    MenuItem.SFX -> when (state.sfx) {
        SfxMode.ON -> "ON"
        SfxMode.OFF -> "OFF"
    }
    MenuItem.MOTOR -> when (state.motor) {
        MotorMode.OFF -> "OFF"
        MotorMode.PLAY -> "PLAY"
        MotorMode.PLAY_REC -> "PLAY+REC"
    }
    MenuItem.MEMO -> when (state.memoQuality) {
        MemoQuality.HI_Q -> "HI-Q"
        MemoQuality.STD -> "STD"
    }
    MenuItem.RATE -> when (state.sampleRateMode) {
        SampleRateMode.R48 -> "48"
        SampleRateMode.R96 -> "96"
    }
    MenuItem.DISK -> "${state.diskFreeGb}/${state.diskTotalGb}"
    MenuItem.NAME -> state.deviceName.padEnd(4, ' ').take(4)
    MenuItem.TIME -> state.time                       // "HH:MM"
    MenuItem.DATE -> state.date                       // "dd/mm/yy"
    MenuItem.VER -> state.firmwareVersion             // "0.1.0"
    MenuItem.RESET -> "TURN"
    MenuItem.BATT -> "${state.batteryPct}%"
}

// ---------------------------------------------------------------------------
//  Compose dispatcher.
// ---------------------------------------------------------------------------

/**
 * Convenience entry invoked from the OLED dispatcher whenever the menu is open: shows the
 * value page when a setting is open, otherwise the scrolling list.
 */
@Composable
fun OledMenu(state: DeckUiState, modifier: Modifier = Modifier) {
    if (state.settingOpen) SettingPage(state, modifier) else SystemMenuList(state, modifier)
}

// ---------------------------------------------------------------------------
//  The scrolling icon list (manual page 15).
// ---------------------------------------------------------------------------

/**
 * The vertical scrolling list of all 17 items. The window is centred on the selected row:
 * the selection sits on the middle line with its full 16×16 tile + an inverted label bar,
 * the rows immediately above and below peek in dimly, and a scrollbar nub tracks the cursor
 * on the right rail.
 */
@Composable
fun SystemMenuList(state: DeckUiState, modifier: Modifier = Modifier) {
    val items = MenuItem.entries
    val sel = state.menuIndex.coerceIn(0, items.size - 1)

    Oled(modifier) {
        // Three visible rows: previous, selected (centre), next. 16px tiles on a 32px panel.
        val rowH = 16
        val gapY = 0
        val tileX = 1
        val labelX = tileX + 16 + 3      // label starts just right of the tile
        // Centre row vertically; neighbours half-clipped top/bottom.
        val centerY = (rows - rowH) / 2  // = 8
        val rowYs = intArrayOf(centerY - rowH - gapY, centerY, centerY + rowH + gapY)

        for (slot in 0..2) {
            val idx = sel - 1 + slot
            if (idx < 0 || idx >= items.size) continue
            val item = items[idx]
            val y = rowYs[slot]
            val selected = idx == sel

            // The 16×16 white rounded tile + black icon (drawn for every visible row).
            menuTile(tileX, y, menuIcon(item))

            val label = menuLabel(item)
            if (selected) {
                // Inverted highlight bar behind the selected label, full label run.
                val lw = textWidth(label, Font.SMALL)
                val bx = labelX - 2
                val bw = lw + 4
                val bh = 7 + 2
                val by = y + (rowH - bh) / 2
                roundBox(bx, by, bw, bh, lit = true)
                text(labelX, by + 1, label, Font.SMALL, lit = false)
            } else {
                // Neighbour rows: plain label, vertically centred on the tile.
                text(labelX, y + (rowH - 7) / 2, label, Font.SMALL, lit = true)
            }
        }

        // Selection caret on the far-left margin of the centre row (points at the tile).
        // (kept inboard of the tile so it never collides with the icon)

        // Scrollbar rail on the right edge: a faint full-height track + a lit nub whose
        // position encodes the cursor across all 17 items.
        scrollbar(state = sel, count = items.size)
    }
}

/** A right-edge scrollbar: 1px track with a 3px lit nub positioned by [state]/[count]. */
private fun PixelCanvas.scrollbar(state: Int, count: Int) {
    val railX = cols - 2
    val top = 1
    val bottom = rows - 2
    val trackH = bottom - top + 1
    // Dim track ticks every other pixel so it reads as a rail, not a solid bar.
    var ry = top
    while (ry <= bottom) { set(railX, ry, true); ry += 2 }
    // Nub.
    val nubH = 5
    val travel = (trackH - nubH).coerceAtLeast(0)
    val ny = top + if (count <= 1) 0 else travel * state / (count - 1)
    fillRect(railX - 1, ny, 2, nubH, lit = true)
}

// ---------------------------------------------------------------------------
//  The per-item settings page (manual pages 16–17).
// ---------------------------------------------------------------------------

/**
 * The value page for the selected item: the 16×16 tile and SMALL label across the top, the
 * current value on the BIG/SMALL value line below. Multi-field pages (TIME / DATE / NAME)
 * invert the field the cursor is on (state.settingFieldIndex); RESET shows the rotate-to-
 * confirm dial; readouts (DISK / VER / BATT) just print their value.
 */
@Composable
fun SettingPage(state: DeckUiState, modifier: Modifier = Modifier) {
    val item = MenuItem.entries[state.menuIndex.coerceIn(0, MenuItem.entries.size - 1)]

    Oled(modifier) {
        // --- header: tile (top-left) + SMALL label to its right ---
        val tileX = 1
        val tileY = 1
        menuTile(tileX, tileY, menuIcon(item))
        val labelX = tileX + 16 + 4
        text(labelX, tileY + 5, menuLabel(item), Font.SMALL, lit = true)

        // --- value zone (below the header) ---
        val valueTop = 18
        when (item) {
            MenuItem.TIME -> drawTime(state, valueTop)
            MenuItem.DATE -> drawDate(state, valueTop)
            MenuItem.NAME -> drawName(state, valueTop)
            MenuItem.RESET -> drawReset(valueTop)
            MenuItem.RATE -> drawRate(state, valueTop)
            MenuItem.BATT -> drawBattValue(state, valueTop)
            MenuItem.VER -> drawCentered(state.firmwareVersion, Font.BIG, valueTop)
            MenuItem.VIB -> drawVibration(state, valueTop)
            MenuItem.LEDS -> {
                if (state.playerStyle == PlayerStyle.ADVANCED) {
                    drawLeds(state, valueTop)
                } else {
                    val v = settingValueText(item, state)
                    val big = textWidth(v, Font.BIG)
                    if (big <= cols - 2) drawCentered(v, Font.BIG, valueTop)
                    else drawCentered(v, Font.SMALL, valueTop + 2)
                }
            }
            else -> {
                // Single enum value: centred. Use BIG for short tokens, SMALL when too wide.
                val v = settingValueText(item, state)
                val big = textWidth(v, Font.BIG)
                if (big <= cols - 2) drawCentered(v, Font.BIG, valueTop)
                else drawCentered(v, Font.SMALL, valueTop + 2)
            }
        }
    }
}

// ---- value-line renderers --------------------------------------------------

/** Centre [s] horizontally on the panel at row [top] in [font]. */
private fun PixelCanvas.drawCentered(s: String, font: Font, top: Int) {
    val w = textWidth(s, font)
    val x = ((cols - w) / 2).coerceAtLeast(0)
    text(x, top, s, font, lit = true)
}

/** TIME `HH:MM` — two BIG digit fields around a colon; the active field is inverted. */
private fun PixelCanvas.drawTime(state: DeckUiState, top: Int) {
    val parts = state.time.split(":")
    val hh = parts.getOrElse(0) { "00" }
    val mm = parts.getOrElse(1) { "00" }
    val active = state.settingFieldIndex.coerceIn(0, 1)
    // Lay out: [hh][:][mm] in BIG, centred as a block.
    val colonW = textWidth(":", Font.BIG)
    val hhW = textWidth(hh, Font.BIG)
    val mmW = textWidth(mm, Font.BIG)
    val total = hhW + colonW + mmW
    var x = ((cols - total) / 2).coerceAtLeast(0)
    x = drawField(x, top, hh, Font.BIG, inverted = active == 0)
    text(x, top, ":", Font.BIG, lit = true); x += colonW
    drawField(x, top, mm, Font.BIG, inverted = active == 1)
}

/**
 * DATE `dd/mm/yy` — three SMALL fields split on `/`; the active field is inverted. SMALL keeps
 * all eight glyphs comfortably inside the 64-wide panel (BIG would run edge-to-edge).
 */
private fun PixelCanvas.drawDate(state: DeckUiState, top: Int) {
    val seg = state.date.split("/")
    val dd = seg.getOrElse(0) { "01" }
    val mm = seg.getOrElse(1) { "01" }
    val yy = seg.getOrElse(2) { "80" }
    val active = state.settingFieldIndex.coerceIn(0, 2)
    val y = top + 2                                   // nudge down to sit on the BIG baseline
    val slashW = textWidth("/", Font.SMALL)
    val w = textWidth(dd, Font.SMALL) + slashW + textWidth(mm, Font.SMALL) + slashW + textWidth(yy, Font.SMALL)
    var x = ((cols - w) / 2).coerceAtLeast(0)
    x = drawField(x, y, dd, Font.SMALL, inverted = active == 0)
    text(x, y, "/", Font.SMALL, lit = true); x += slashW
    x = drawField(x, y, mm, Font.SMALL, inverted = active == 1)
    text(x, y, "/", Font.SMALL, lit = true); x += slashW
    drawField(x, y, yy, Font.SMALL, inverted = active == 2)
}

/** NAME 4-char editor — four SMALL-ish BIG chars; the active character is inverted. */
private fun PixelCanvas.drawName(state: DeckUiState, top: Int) {
    val name = state.deviceName.padEnd(4, ' ').take(4)
    val active = state.settingFieldIndex.coerceIn(0, 3)
    val chW = textWidth("0", Font.BIG)
    val advance = chW + 1
    val total = advance * 4 - 1
    var x = ((cols - total) / 2).coerceAtLeast(0)
    for (i in 0 until 4) {
        drawField(x, top, name[i].toString(), Font.BIG, inverted = i == active)
        x += advance
    }
}

/** RATE — BIG number + a small `KHZ` unit to its right (spec mark, not brand). */
private fun PixelCanvas.drawRate(state: DeckUiState, top: Int) {
    val n = if (state.sampleRateMode == SampleRateMode.R96) "96" else "48"
    val unit = "KHZ"
    val nW = textWidth(n, Font.BIG)
    val uW = textWidth(unit, Font.SMALL)
    val total = nW + 2 + uW
    var x = ((cols - total) / 2).coerceAtLeast(0)
    text(x, top, n, Font.BIG, lit = true)
    x += nW + 2
    text(x, top + 3, unit, Font.SMALL, lit = true)
}

/** BATT — battery glyph + `NN%` underneath (functional readout). */
private fun PixelCanvas.drawBattValue(state: DeckUiState, top: Int) {
    val pct = "${state.batteryPct}%"
    val pw = textWidth(pct, Font.SMALL)
    val totalW = 18 + 4 + pw
    val startX = (cols - totalW) / 2
    battery(startX, top + 1, state.batteryPct, charging = state.charging)
    text(startX + 18 + 4, top + 2, pct, Font.SMALL, lit = true)
}

/** RESET — the rotate-reel-to-confirm dial: a circular arrow + SMALL `TURN`. */
private fun PixelCanvas.drawReset(top: Int) {
    val prompt = "TURN"
    val pw = textWidth(prompt, Font.SMALL)
    val totalW = 13 + 4 + pw
    val startX = (cols - totalW) / 2
    val arrow = listOf(
        "...#####.....",
        "..#.....#..#.",
        ".#.......#.#.",
        ".#........##.",
        "#.........###",
        "#............",
        "#............",
        "#.........#..",
        ".#........#..",
        ".#.......#...",
        "..#.....#....",
        "...#####.....",
    )
    val gy = top + 1
    for (r in arrow.indices) {
        val row = arrow[r]
        for (c in row.indices) if (row[c] == '#') set(startX + c, gy + r, true)
    }
    text(startX + 13 + 4, top + 4, prompt, Font.SMALL, lit = true)
}

/**
 * Draw a value field, optionally as an inverted token (white box, glyphs knocked out).
 * Returns the x cursor just past the field. Used for the active multi-field cursor.
 */
private fun PixelCanvas.drawField(x: Int, y: Int, s: String, font: Font, inverted: Boolean): Int {
    val w = textWidth(s, font)
    val h = if (font == Font.BIG) 10 else 7
    return if (inverted) {
        val padX = 1
        val padY = 1
        val bw = w + padX * 2
        val bh = h + padY * 2
        roundBox(x, y - padY, bw, bh, lit = true)
        text(x + padX, y, s, font, lit = false)
        x + w + padX           // advance by glyph run (box pad overlaps neighbour spacing)
    } else {
        text(x, y, s, font, lit = true)
        x + w
    }
}

/** VIB — percentage + horizontal slider bar below it. */
private fun PixelCanvas.drawVibration(state: DeckUiState, top: Int) {
    val pct = "${(state.vibrationStrength * 100).roundToInt()}%"
    val w = textWidth(pct, Font.SMALL)
    val x = ((cols - w) / 2).coerceAtLeast(0)
    text(x, top, pct, Font.SMALL, lit = true)

    // Draw a nice horizontal slider/bar under the percentage.
    val barW = 48
    val barH = 3
    val barX = (cols - barW) / 2
    val barY = top + 9

    // Draw the outline/track.
    rectOutline(barX, barY, barW, barH, lit = true)

    // Fill the bar according to the value.
    val fillW = (barW * state.vibrationStrength).roundToInt().coerceIn(0, barW)
    if (fillW > 0) {
        fillRect(barX, barY, fillW, barH, lit = true)
    }
}

/** LEDS dual setting: brightness (LOW/MED/HIGH) + tape flutter (OFF/ON) + pitch ramps (OFF/ON) + slip mode (OFF/ON) */
private fun PixelCanvas.drawLeds(state: DeckUiState, top: Int) {
    val active = state.settingFieldIndex.coerceIn(0, 3)
    val ledText = when (state.leds) {
        LedBrightness.LOW -> "LOW"
        LedBrightness.MEDIUM -> "MED"
        LedBrightness.HIGH -> "HIGH"
    }
    val flutterText = when (state.tapeFlutter) {
        TapeFlutterMode.OFF -> "OFF"
        TapeFlutterMode.ON -> "ON"
    }
    val rampText = when (state.pitchRamp) {
        PitchRampMode.OFF -> "OFF"
        PitchRampMode.ON -> "ON"
    }
    val slipText = when (state.slipMode) {
        SlipMode.OFF -> "OFF"
        SlipMode.ON -> "ON"
    }
    
    // Row 1 (y = 18): L: [value] and F: [value]
    val y1 = top
    var x = 2
    text(x, y1, "L:", Font.SMALL, lit = true)
    x += textWidth("L:", Font.SMALL)
    x = drawField(x, y1, ledText, Font.SMALL, inverted = active == 0)

    x = 34
    text(x, y1, "F:", Font.SMALL, lit = true)
    x += textWidth("F:", Font.SMALL)
    drawField(x, y1, flutterText, Font.SMALL, inverted = active == 1)

    // Row 2 (y = 25): R: [value] and S: [value]
    val y2 = top + 7
    x = 2
    text(x, y2, "R:", Font.SMALL, lit = true)
    x += textWidth("R:", Font.SMALL)
    x = drawField(x, y2, rampText, Font.SMALL, inverted = active == 2)

    x = 34
    text(x, y2, "S:", Font.SMALL, lit = true)
    x += textWidth("S:", Font.SMALL)
    drawField(x, y2, slipText, Font.SMALL, inverted = active == 3)
}
