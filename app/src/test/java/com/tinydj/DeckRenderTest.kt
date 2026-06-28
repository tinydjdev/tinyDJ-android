package com.tinydj

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tinydj.ui.deck.DeckActions
import com.tinydj.ui.deck.DeckContent
import com.tinydj.ui.deck.DeckUiState
import com.tinydj.ui.deck.LcdMode
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
 * Not a real test — a headless renderer. Robolectric NATIVE graphics rasterizes the
 * deck to a PNG so the design can be reviewed without a device. We draw the decor
 * view straight to a Bitmap (captureToImage() would block forever on the reel's
 * endless animation loop).
 *   gradle :app:testDebugUnitTest --tests com.tinydj.DeckRenderTest
 * Output: app/build/render/deck.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DeckRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderDeck() {
        val state = DeckUiState(
            hasTrack = true,
            title = "demo take 01",
            artist = "tinydj",
            isPlaying = true,
            positionMs = 63_000,
            durationMs = 192_000,
            positionFrames = 63_000L * 48,
            totalFrames = 192_000L * 48,
            fileSampleRate = 48_000,
            progress = 0.33f,
            speed = 1f,
            volume = 0.8f,
            loop = false,
            lcdMode = LcdMode.TIME,
        )

        compose.mainClock.autoAdvance = false
        compose.setContent {
            TinyDjTheme { DeckContent(state = state, actions = DeckActions()) }
        }
        repeat(6) { compose.mainClock.advanceTimeByFrame() }

        val view = compose.activity.window.decorView
        val w = view.width.takeIf { it > 0 } ?: 822
        val h = view.height.takeIf { it > 0 } ?: 1782
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))

        val out = File("/app/app/build/render/deck.png")
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENDER_WROTE ${out.absolutePath} ${w}x${h}")
    }
}
