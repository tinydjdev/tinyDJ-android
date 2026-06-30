package com.tinydj.ui.deck

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.floor
import com.tinydj.ui.theme.OledOnColor
import com.tinydj.ui.theme.OledOffColor

/**
 * The OLED pixel primitive — a real low-res dot-matrix renderer for the device's
 * monochrome firmware screens.
 *
 * Everything is "set pixel (x, y)": one lit cell is a single axis-aligned [DrawScope.drawRect]
 * on an integer grid. NO anti-aliasing, NO glow/bloom — hard square edges only. Two built-in
 * bitmap fonts (SMALL 5x7, BIG 7x10) are drawn as filled rects, pixel icons are explicit 1-bit
 * maps, and inverted tokens are white rounded boxes with glyphs knocked out in black.
 *
 * Logical panel is [OLED_COLS] x [OLED_ROWS] (64 x 48). All coordinates below are in grid cells.
 */

const val OLED_COLS = 64
const val OLED_ROWS = 32

/** SMALL = 5x7 (advance 6). BIG = 7x10 (advance 8). */
enum class Font { SMALL, BIG }

// ---------------------------------------------------------------------------
// PixelCanvas — the low-level grid blitter.
// ---------------------------------------------------------------------------

/**
 * A fixed [cols] x [rows] dot-matrix surface mapped onto a [DrawScope]. Each cell is drawn as a
 * hard-edged rect of side [px] (minus [gap]) at integer offsets from [origin]. There is no
 * sub-pixel positioning and no anti-aliasing.
 */
class PixelCanvas(
    val cols: Int,
    val rows: Int,
    private val scope: DrawScope,
    private val origin: Offset,
    private val px: Float,
    private val on: Color,
    private val off: Color,
    private val gap: Float = 0f,
) {
    private var clipXStart: Int = 0
    private var clipXEnd: Int = cols
    private var clipYStart: Int = 0
    private var clipYEnd: Int = rows

    fun setClipX(start: Int, end: Int) {
        clipXStart = start
        clipXEnd = end
    }

    fun clearClipX() {
        clipXStart = 0
        clipXEnd = cols
    }

    fun setClipY(start: Int, end: Int) {
        clipYStart = start
        clipYEnd = end
    }

    fun clearClipY() {
        clipYStart = 0
        clipYEnd = rows
    }

    /** Paint the whole panel with the off (background) color. */
    fun clear() {
        scope.drawRect(
            color = off,
            topLeft = origin,
            size = Size(cols * px, rows * px),
        )
    }

    /** Set a single pixel. Off-grid coordinates are ignored. */
    fun set(x: Int, y: Int, lit: Boolean = true) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return
        if (x < clipXStart || x >= clipXEnd) return
        if (y < clipYStart || y >= clipYEnd) return
        scope.drawRect(
            color = if (lit) on else off,
            topLeft = Offset(origin.x + x * px, origin.y + y * px),
            size = Size(px - gap, px - gap),
        )
    }

    /** Filled rectangle (inclusive of the top-left cell). */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, lit: Boolean = true) {
        for (gy in 0 until h) for (gx in 0 until w) set(x + gx, y + gy, lit)
    }

    /** Single-cell-thick rectangle outline. */
    fun rectOutline(x: Int, y: Int, w: Int, h: Int, lit: Boolean = true) {
        if (w <= 0 || h <= 0) return
        for (gx in 0 until w) {
            set(x + gx, y, lit)
            set(x + gx, y + h - 1, lit)
        }
        for (gy in 0 until h) {
            set(x, y + gy, lit)
            set(x + w - 1, y + gy, lit)
        }
    }

    /** Filled rounded box: a filled rect with the four corner pixels cleared back to [off]. */
    fun roundBox(x: Int, y: Int, w: Int, h: Int, lit: Boolean = true) {
        fillRect(x, y, w, h, lit)
        if (w >= 2 && h >= 2) {
            set(x, y, !lit)
            set(x + w - 1, y, !lit)
            set(x, y + h - 1, !lit)
            set(x + w - 1, y + h - 1, !lit)
        }
    }
}

// ---------------------------------------------------------------------------
// Fonts — bitmaps transcribed verbatim from BUILD SPEC D.2.
// Stored as Map<Char, IntArray>: one int per row, low bit = leftmost column.
// ---------------------------------------------------------------------------

/** Parse a glyph given as rows of `#`(lit)/`.`(off), low bit = leftmost column. */
private fun glyph(vararg rowStrings: String): IntArray =
    IntArray(rowStrings.size) { r ->
        var bits = 0
        val row = rowStrings[r]
        for (c in row.indices) if (row[c] == '#') bits = bits or (1 shl c)
        bits
    }

/** SMALL 5x7 font. Width 5, height 7, advance 6. */
private val SMALL_5x7: Map<Char, IntArray> = buildMap {
    put('0', glyph(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."))
    put('1', glyph("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."))
    put('2', glyph(".###.", "#...#", "...#.", "..#..", ".#...", "#....", "#####"))
    put('3', glyph(".###.", "#...#", "...#.", ".##..", "...#.", "#...#", ".###."))
    put('4', glyph("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."))
    put('5', glyph("#####", "#....", "#....", "####.", "....#", "#...#", ".###."))
    put('6', glyph(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."))
    put('7', glyph("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."))
    put('8', glyph(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."))
    put('9', glyph(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".###."))
    put('A', glyph(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"))
    put('B', glyph("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."))
    put('C', glyph(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."))
    put('D', glyph("###..", "#..#.", "#...#", "#...#", "#...#", "#..#.", "###.."))
    put('E', glyph("#####", "#....", "#....", "###..", "#....", "#....", "#####"))
    put('F', glyph("#####", "#....", "#....", "###..", "#....", "#....", "#...."))
    put('G', glyph(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"))
    put('H', glyph("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"))
    put('I', glyph(".###.", "..#..", "..#..", "..#..", "..#..", "..#..", ".###."))
    put('J', glyph("..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."))
    put('K', glyph("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"))
    put('L', glyph("#....", "#....", "#....", "#....", "#....", "#....", "#####"))
    put('M', glyph("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"))
    put('N', glyph("#...#", "##..#", "#.#.#", "#.#.#", "#..##", "#...#", "#...#"))
    put('O', glyph(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."))
    put('P', glyph("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."))
    put('Q', glyph(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"))
    put('R', glyph("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"))
    put('S', glyph(".###.", "#...#", "#....", ".###.", "....#", "#...#", ".###."))
    put('T', glyph("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."))
    put('U', glyph("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."))
    put('V', glyph("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."))
    put('W', glyph("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"))
    put('X', glyph("#...#", "#...#", ".#.#.", "..#..", "..#..", ".#.#.", "#...#"))
    put('Y', glyph("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."))
    put('Z', glyph("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"))
    put(':', glyph(".....", "..#..", ".....", ".....", ".....", "..#..", "....."))
    put('-', glyph(".....", ".....", ".....", "#####", ".....", ".....", "....."))
    put('.', glyph(".....", ".....", ".....", ".....", ".....", "..#..", "....."))
    put('%', glyph("#...#", "#..#.", "..#..", ".#.#.", ".#..#", "#...#", "....."))
    put('+', glyph(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."))
    put('/', glyph("....#", "...#.", "..#..", "..#..", ".#...", "#....", "#...."))
    put('#', glyph(".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#."))
    put('?', glyph(".###.", "#...#", "...#.", "..#..", "..#..", ".....", "..#.."))
    put('!', glyph("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."))
    put(',', glyph(".....", ".....", ".....", ".....", "..#..", "..#..", ".#..."))
    put('(', glyph("...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#."))
    put(')', glyph(".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#..."))
    put('*', glyph(".....", "#.#.#", ".###.", "#####", ".###.", "#.#.#", "....."))
    put(' ', glyph(".....", ".....", ".....", ".....", ".....", ".....", "....."))
}

private const val SMALL_W = 5
private const val SMALL_H = 7   // advance = width + tracking(1) = 6, per spec

private const val BIG_W = 7
private const val BIG_H = 10    // advance = width + tracking(1) = 8, per spec

/** BIG 7x10 — digits + `.` `:` `-` are explicit; letters are scaled from SMALL via [scaleGlyph]. */
private val BIG_DIGITS: Map<Char, IntArray> = buildMap {
    fun big9(vararg r9: String): IntArray = glyph(*(r9.toList() + ".......").toTypedArray())
    put('0', big9(".#####.", "##...##", "##...##", "##..###", "##.#.##", "###..##", "##...##", "##...##", ".#####."))
    put('1', big9("...##..", "..###..", "....##.", "....##.", "....##.", "....##.", "....##.", "....##.", "..####."))
    put('2', big9(".#####.", "##...##", ".....##", "....##.", "...##..", "..##...", ".##....", "##.....", "#######"))
    put('3', big9(".#####.", "##...##", ".....##", "..####.", ".....##", ".....##", "##...##", "##...##", ".#####."))
    put('4', big9("#....#.", "#....#.", "#....#.", "#....#.", "#######", ".....#.", ".....#.", ".....#.", ".....#."))
    put('5', big9("#######", "##.....", "##.....", "######.", ".....##", ".....##", "##...##", "##...##", ".#####."))
    put('6', big9(".#####.", "##...##", "##.....", "######.", "##...##", "##...##", "##...##", "##...##", ".#####."))
    put('7', big9("#######", ".....##", "....##.", "...##..", "..##...", ".##....", ".##....", ".##....", ".##...."))
    put('8', big9(".#####.", "##...##", "##...##", ".#####.", "##...##", "##...##", "##...##", "##...##", ".#####."))
    put('9', big9(".#####.", "##...##", "##...##", ".######", ".....##", ".....##", "##...##", "##...##", ".#####."))
    put('.', glyph(".......", ".......", ".......", ".......", ".......", ".......", ".......", ".......", "..##...", "..##..."))
    put(':', glyph(".......", ".......", ".......", "..##...", "..##...", ".......", ".......", ".......", "..##...", "..##..."))
    put('-', glyph(".......", ".......", ".......", ".......", "#######", "#######", ".......", ".......", ".......", "......."))
    put(' ', IntArray(BIG_H) { 0 })
}

/**
 * Widen a SMALL 5x7 glyph to 7x10: add a blank column each side (5 -> 7) and stretch
 * 7 rows -> 10 by duplicating rows 2, 4, 6 (0-based 1, 3, 5). Hard nearest-neighbor only.
 */
private fun scaleGlyph(small: IntArray): IntArray {
    // Horizontal: shift each 5-bit row right by one to inset a blank left column (and a blank right).
    val widened = IntArray(small.size) { (small[it] shl 1) and 0x7F }
    // Vertical: duplicate source rows 1, 3, 5 to go from 7 -> 10 rows.
    val dup = setOf(1, 3, 5)
    val out = ArrayList<Int>(BIG_H)
    for (r in widened.indices) {
        out.add(widened[r])
        if (r in dup) out.add(widened[r])
    }
    return IntArray(BIG_H) { out.getOrElse(it) { 0 } }
}

/** Resolve the bitmap for a char in a given font (BIG letters are scaled on demand). */
private fun glyphFor(c: Char, font: Font): IntArray {
    val key = c.uppercaseChar()
    return when (font) {
        Font.SMALL -> SMALL_5x7[key] ?: SMALL_5x7[' ']!!
        Font.BIG -> BIG_DIGITS[key] ?: SMALL_5x7[key]?.let(::scaleGlyph) ?: BIG_DIGITS[' ']!!
    }
}

private fun fontHeight(font: Font) = if (font == Font.BIG) BIG_H else SMALL_H
private fun fontWidth(font: Font) = if (font == Font.BIG) BIG_W else SMALL_W

// ---------------------------------------------------------------------------
// Text drawing helpers.
// ---------------------------------------------------------------------------

/** Draw [s] at cell (x, y). Returns the total width drawn in cells. */
fun PixelCanvas.text(
    x: Int,
    y: Int,
    s: String,
    font: Font = Font.SMALL,
    lit: Boolean = true,
    tracking: Int = 1,
): Int {
    val h = fontHeight(font)
    val w = fontWidth(font)
    val advance = w + tracking
    var cx = x
    for (ch in s) {
        val bmp = glyphFor(ch, font)
        for (row in 0 until h) {
            val bits = bmp.getOrElse(row) { 0 }
            for (col in 0 until w) {
                if ((bits shr col) and 1 == 1) set(cx + col, y + row, lit)
            }
        }
        cx += advance
    }
    return (cx - x - tracking).coerceAtLeast(0)
}

/** Width in cells that [s] would occupy in [font]. */
fun PixelCanvas.textWidth(s: String, font: Font, tracking: Int = 1): Int {
    if (s.isEmpty()) return 0
    val advance = fontWidth(font) + tracking
    return s.length * advance - tracking
}

/**
 * The device's signature inverted token: a white (lit) rounded box with [s] knocked out in
 * black. Returns the box width drawn.
 */
fun PixelCanvas.boxedText(
    x: Int,
    y: Int,
    s: String,
    font: Font = Font.SMALL,
    padX: Int = 2,
    padY: Int = 1,
    round: Boolean = true,
): Int {
    val tw = textWidth(s, font)
    val th = fontHeight(font)
    val bw = tw + padX * 2
    val bh = th + padY * 2
    if (round) roundBox(x, y, bw, bh, lit = true) else fillRect(x, y, bw, bh, lit = true)
    text(x + padX, y + padY, s, font, lit = false)
    return bw
}

// ---------------------------------------------------------------------------
// Icons / glyphs — explicit 1-bit pixel maps (BUILD SPEC D.3).
// ---------------------------------------------------------------------------

/** Stamp an explicit `#`/`.` pixel-map at (x, y) in the given orientation. */
private fun PixelCanvas.stamp(x: Int, y: Int, rows: List<String>, lit: Boolean = true) {
    for (r in rows.indices) {
        val row = rows[r]
        for (c in row.indices) if (row[c] == '#') set(x + c, y + r, lit)
    }
}

/**
 * Battery glyph, 18x9: rounded body outline + right terminal nub + inner fill bar whose width
 * is proportional to [level] (0..100). When [charging], a lightning bolt is knocked out of the fill.
 */
fun PixelCanvas.battery(x: Int, y: Int, level: Int, charging: Boolean = false) {
    val bodyW = 16
    val bodyH = 9
    // Body outline (rounded corners).
    rectOutline(x, y, bodyW, bodyH, lit = true)
    set(x, y, false)
    set(x + bodyW - 1, y, false)
    set(x, y + bodyH - 1, false)
    set(x + bodyW - 1, y + bodyH - 1, false)
    // Right terminal nub.
    val nubX = x + bodyW
    val nubY = y + bodyH / 2 - 1
    fillRect(nubX, nubY, 2, 3, lit = true)
    // Inner fill bar.
    val innerX = x + 2
    val innerY = y + 2
    val innerW = bodyW - 4
    val innerH = bodyH - 4
    val fillW = (innerW * level.coerceIn(0, 100) + 99) / 100
    if (fillW > 0) fillRect(innerX, innerY, fillW, innerH, lit = true)
    // Charging bolt punched out of the fill.
    if (charging) {
        val bx = x + bodyW / 2 - 1
        stamp(
            bx, y + 2,
            listOf(
                "..#",
                ".##",
                "###",
                ".##",
                "##.",
            ),
            lit = false,
        )
    }
}

/**
 * Horizontal tick ruler used by the varispeed screen: a baseline with tall ticks every
 * [majorEvery] cells, short minor ticks between, and a deeper center notch (the 0 detent).
 */
fun PixelCanvas.ruler(x: Int, y: Int, w: Int, majorEvery: Int = 5) {
    // Baseline.
    for (i in 0 until w) set(x + i, y, true)
    val center = w / 2
    for (i in 0..w) {
        val tall = i % majorEvery == 0
        val h = if (tall) 3 else 1
        for (j in 1..h) set(x + i, y - j, true)
    }
    // Deeper center notch = 0 detent.
    for (j in 1..5) set(x + center, y - j, true)
}

/** The 64-segment ramp wedge used by VOLUME (right-growing filled triangle). */
fun PixelCanvas.wedge(x: Int, y: Int, w: Int, h: Int, fill: Float) {
    val lit = (w * fill.coerceIn(0f, 1f)).toInt()
    for (col in 0 until w) {
        val colH = ((col + 1).toFloat() / w * h).toInt().coerceAtLeast(1)
        val top = y + h - colH
        val on = col < lit
        for (row in 0 until colH) set(x + col, top + row, on)
    }
}

// ---- Menu tiles -----------------------------------------------------------

enum class MenuIcon {
    EDIT, PLAY, LEDS, VIB, SPK, SFX, MOTOR, MEMO,
    RATE, DISK, NAME, TIME, DATE, VER, RESET, BATT
}

/**
 * 16x16 white rounded menu tile with a centered ~12x12 black icon knocked out (BUILD SPEC D.3).
 * NAME renders `TJ` (de-branded — never the real model glyphs).
 */
fun PixelCanvas.menuTile(x: Int, y: Int, icon: MenuIcon, size: Int = 16) {
    roundBox(x, y, size, size, lit = true)
    // soften the rounded tile a touch more
    set(x + 1, y + 1, false); set(x + size - 2, y + 1, false)
    set(x + 1, y + size - 2, false); set(x + size - 2, y + size - 2, false)
    val ix = x + 2
    val iy = y + 2
    val rows = menuIconBitmap(icon)
    stamp(ix, iy, rows, lit = false)
}

/** 12x12 black-on-white icon maps for the 17 settings tiles. `#` = black (knocked out). */
private fun menuIconBitmap(icon: MenuIcon): List<String> = when (icon) {

    MenuIcon.EDIT -> listOf( // bar + checker
        "............",
        ".########...",
        "............",
        "...#.#.#.#..",
        "...#.#.#.#..",
        ".#.#.#.#.#..",
        ".#.#.#.#....",
        "............",
        ".########...",
        "............",
        "............",
        "............",
    )
    MenuIcon.PLAY -> listOf( // triangle
        "............",
        "...##.......",
        "...####.....",
        "...######...",
        "...########.",
        "...########.",
        "...######...",
        "...####.....",
        "...##.......",
        "............",
        "............",
        "............",
    )

    MenuIcon.LEDS -> listOf( // brightness bars
        "............",
        ".#..##..###.",
        ".#..##..###.",
        ".#..##..###.",
        ".#..##..###.",
        ".#..##..###.",
        ".#..##..###.",
        ".#..##..###.",
        ".#..##..###.",
        "............",
        "............",
        "............",
    )
    MenuIcon.VIB -> listOf( // haptics / waves
        "............",
        "...#....#...",
        "..#.#..#.#..",
        ".#.#....#.#.",
        ".#.#.##.#.#.",
        "#.#.####.#.#",
        "#.#.####.#.#",
        ".#.#.##.#.#.",
        ".#.#....#.#.",
        "..#.#..#.#..",
        "...#....#...",
        "............",
    )
    MenuIcon.SPK -> listOf( // cone + waves
        "......#.....",
        ".....##.#...",
        "...###..#.#.",
        "..####.#.#.#",
        "..####.#.#.#",
        "..####.#.#.#",
        "..####.#.#.#",
        "...###..#.#.",
        ".....##.#...",
        "......#.....",
        "............",
        "............",
    )
    MenuIcon.SFX -> listOf( // cone + checker
        "......#.....",
        ".....##.....",
        "...###...#.#",
        "..####..#.#.",
        "..####...#.#",
        "..####..#.#.",
        "..####...#.#",
        "...###..#.#.",
        ".....##..#.#",
        "......#.....",
        "............",
        "............",
    )
    MenuIcon.MOTOR -> listOf( // ring + center hub
        "....####....",
        "..##....##..",
        ".#........#.",
        ".#...##...#.",
        "#...####...#",
        "#...####...#",
        "#...####...#",
        ".#...##...#.",
        ".#........#.",
        "..##....##..",
        "....####....",
        "............",
    )
    MenuIcon.MEMO -> listOf( // bold M
        "............",
        "#.........#.",
        "##.......##.",
        "#.#.....#.#.",
        "#..#...#..#.",
        "#...#.#...#.",
        "#....#....#.",
        "#.........#.",
        "#.........#.",
        "#.........#.",
        "............",
        "............",
    )
    MenuIcon.RATE -> listOf( // "kHz"
        "............",
        ".#...#......",
        ".#..#..#.#..",
        ".#.#...#.#..",
        ".##....###..",
        ".#.#...#.#..",
        ".#..#..#.#..",
        ".#...#......",
        "..........#.",
        ".......###..",
        "............",
        "............",
    )
    MenuIcon.DISK -> listOf( // dither platter
        "....####....",
        "..##.#.#.##.",
        ".#.#.#.#.#.#",
        "#.#.#.#.#.#.",
        "#.#.#.##.#.#",
        "#.#.#.##.#.#",
        "#.#.#.#.#.#.",
        ".#.#.#.#.#.#",
        "..##.#.#.##.",
        "....####....",
        "............",
        "............",
    )
    MenuIcon.NAME -> listOf( // "TJ" — DE-BRAND (never the real model glyphs)
        "............",
        ".#####......",
        "...#........",
        "...#...###..",
        "...#....#...",
        "...#....#...",
        "...#....#...",
        "...#.#..#...",
        ".....##.....",
        "............",
        "............",
        "............",
    )
    MenuIcon.TIME -> listOf( // clock + hands
        "....####....",
        "..##....##..",
        ".#...#....#.",
        ".#...#....#.",
        "#....#.....#",
        "#....####..#",
        "#..........#",
        ".#........#.",
        ".#........#.",
        "..##....##..",
        "....####....",
        "............",
    )
    MenuIcon.DATE -> listOf( // bold "24"
        "............",
        ".###...#..#.",
        "#...#..#..#.",
        "....#..#..#.",
        "...#...####.",
        "..#.......#.",
        ".#........#.",
        "#####.....#.",
        "............",
        "............",
        "............",
        "............",
    )
    MenuIcon.VER -> listOf( // chip + sprockets + "1.0"
        "..#.#.#.#...",
        ".##########.",
        ".#........#.",
        ".#..#.....#.",
        ".#.#.#.#..#.",
        ".#..#.....#.",
        ".#.#.#..#.#.",
        ".#........#.",
        ".##########.",
        "..#.#.#.#...",
        "............",
        "............",
    )
    MenuIcon.RESET -> listOf( // card / gear + dot
        ".....##.....",
        "...#.##.#...",
        "..#..##..#..",
        ".##.####.##.",
        ".#..####..#.",
        ".#..####..#.",
        ".##.####.##.",
        "..#..##..#..",
        "...#.##.#...",
        ".....##.....",
        "............",
        "............",
    )
    MenuIcon.BATT -> listOf( // horizontal battery
        "............",
        "............",
        ".#########..",
        ".#.......#.#",
        ".#.#####.#.#",
        ".#.#####.#.#",
        ".#.#####.#.#",
        ".#.......#.#",
        ".#########..",
        "............",
        "............",
        "............",
    )
}

// ---- Mode badges ----------------------------------------------------------

enum class ModeIcon { MEMO_M, REEL, EAR }

/** Large mode badge glyph centered-ish at (x, y) (BUILD SPEC B.1: MEMO=M, REC=twin-reel, LIBRARY=ear). */
fun PixelCanvas.modeIcon(x: Int, y: Int, icon: ModeIcon) {
    when (icon) {
        ModeIcon.MEMO_M -> text(x, y, "M", Font.BIG)
        ModeIcon.REEL -> reel(x, y)
        ModeIcon.EAR -> stamp(
            x, y,
            listOf( // ~13x14 ear: outer curl + inner hook
                "...####....",
                ".##....##..",
                ".#......#..",
                "#...##...#.",
                "#..#..#..#.",
                "#..#...#.#.",
                "#......#.#.",
                "#.....#..#.",
                "#....#...#.",
                "#...#...#..",
                "#..#...#...",
                "#.#...#....",
                ".#...#.....",
                "..###......",
            ),
        )
    }
}

/** Cassette/reel icon, 22x11: two hub circles + two bottom rails. */
fun PixelCanvas.reel(x: Int, y: Int, rails: Boolean = true) {
    fun hub(hx: Int) = stamp(
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
    hub(x + 12)
    if (rails) {
        for (i in 0 until 18) {
            set(x + 1 + i, y + 8, true)
            set(x + 1 + i, y + 10, true)
        }
    }
}

/** USB plug icon, ~11x9, for the MTP screen. */
fun PixelCanvas.usbPlug(x: Int, y: Int) {
    stamp(
        x, y,
        listOf(
            "....#......",
            "...###.....",
            "....#......",
            "..#.#.#....",
            "..#.#.#....",
            "###.#.###..",
            "..#.#.#....",
            "....#......",
            "....#......",
        ),
    )
}

// ---- Transport glyphs (~7x7) ----------------------------------------------

fun PixelCanvas.glyphPlay(x: Int, y: Int) = stamp(
    x, y,
    listOf(
        "#......",
        "###....",
        "#####..",
        "#######",
        "#####..",
        "###....",
        "#......",
    ),
)

fun PixelCanvas.glyphRec(x: Int, y: Int) = stamp(
    x, y,
    listOf(
        "..###..",
        ".#####.",
        "#######",
        "#######",
        "#######",
        ".#####.",
        "..###..",
    ),
)

fun PixelCanvas.glyphStop(x: Int, y: Int) = stamp(
    x, y,
    listOf(
        "#######",
        "#######",
        "#######",
        "#######",
        "#######",
        "#######",
        "#######",
    ),
)

fun PixelCanvas.glyphPause(x: Int, y: Int) = stamp(
    x, y,
    listOf(
        "##...##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
        "##...##",
    ),
)

// ---------------------------------------------------------------------------
// Oled — the Compose host. Allocates the integer pixel grid, centers it, and
// exposes a PixelCanvas draw lambda. Always nearest / hard-edged.
// ---------------------------------------------------------------------------

@Composable
fun Oled(
    modifier: Modifier = Modifier,
    onColor: Color = OledOnColor,
    offColor: Color = OledOffColor,
    gap: Float = 0f,
    draw: PixelCanvas.() -> Unit,
) {
    Canvas(modifier) {
        // Clear the entire canvas bounding box first to prevent aspect mismatch colors from leaking.
        drawRect(color = offColor, topLeft = Offset.Zero, size = size)
        // One device-pixel per grid cell, hard-edged. Never floor below 1: a slightly small
        // panel clips an edge row rather than rendering nothing at all.
        val px = minOf(size.width / OLED_COLS, size.height / OLED_ROWS).coerceAtLeast(1f)
        val gridW = px * OLED_COLS
        val gridH = px * OLED_ROWS
        val origin = Offset(
            x = ((size.width - gridW) / 2f),
            y = ((size.height - gridH) / 2f),
        )
        val canvas = PixelCanvas(
            cols = OLED_COLS,
            rows = OLED_ROWS,
            scope = this,
            origin = origin,
            px = px,
            on = onColor,
            off = offColor,
            gap = gap,
        )
        canvas.clear()
        canvas.draw()
    }
}
