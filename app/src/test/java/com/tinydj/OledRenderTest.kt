package com.tinydj

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tinydj.ui.deck.AppMode
import com.tinydj.ui.deck.DeckUiState
import com.tinydj.ui.deck.MenuItem
import com.tinydj.ui.deck.MiniLcd
import com.tinydj.ui.deck.OledView
import com.tinydj.ui.deck.PlayMode
import com.tinydj.ui.theme.TinyDjTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Headless renderer for the OLED firmware screens ALONE, blown up large so the dot-matrix
 * pixel art (fonts, icons, menu, ruler) can be reviewed without a device.
 *   gradle :app:testDebugUnitTest --tests com.tinydj.OledRenderTest
 * Output: app/build/render/oled.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w480dp-h1680dp-xhdpi")
class OledRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderOled() {
        val base = DeckUiState(powered = true, hasTrack = true)
        val screens = listOf(
            base.copy(oledView = OledView.TITLE),
            base.copy(oledView = OledView.PLAYBACK, positionMs = 63_000, trackNumber = 1, dateLabel = "TODAY"),
            base.copy(oledView = OledView.VARISPEED, varispeed = 200),
            base.copy(oledView = OledView.VARISPEED, varispeed = -50),
            base.copy(oledView = OledView.VOLUME_WEDGE, volume = 0.7f),
            base.copy(oledView = OledView.FILE_INFO, fileSampleRate = 48_000, durationMs = 192_000, trackNumber = 1),
            base.copy(oledView = OledView.BATTERY, batteryPct = 75, charging = true),
            base.copy(menuOpen = true, menuIndex = MenuItem.PLAY.ordinal),
            base.copy(menuOpen = true, settingOpen = true, menuIndex = MenuItem.PLAY.ordinal, playMode = PlayMode.REPEAT),
            base.copy(oledView = OledView.MODE_BADGE, appMode = AppMode.LIBRARY),
        )

        compose.mainClock.autoAdvance = false
        compose.setContent {
            TinyDjTheme {
                Column(
                    Modifier.background(Color(0xFF55585A)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    screens.forEach { s ->
                        // 64x32 grid at px≈3 → 192x96 content inside a 220x110 panel.
                        MiniLcd(state = s, modifier = Modifier.size(width = 220.dp, height = 110.dp))
                    }
                }
            }
        }
        repeat(6) { compose.mainClock.advanceTimeByFrame() }

        val view = compose.activity.window.decorView
        val w = view.width.takeIf { it > 0 } ?: 960
        val h = view.height.takeIf { it > 0 } ?: 3360
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val out = File("/app/app/build/render/oled.png")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("OLED_RENDER_WROTE ${out.absolutePath} ${w}x${h}")
    }
}
