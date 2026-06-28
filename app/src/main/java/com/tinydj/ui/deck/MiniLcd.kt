package com.tinydj.ui.deck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.tinydj.ui.theme.Metal

/**
 * The device's monochrome OLED panel, as a face piece: the near-black glass well + thin bezel ring
 * with the live firmware screen rendered inside it.
 *
 * This is intentionally a *thin wrapper* (CONTRACT §5): the bezel/well chrome lives here, but every
 * pixel of firmware content is delegated to [OledScreen], which switches on
 * [DeckUiState.oledView] and paints the matching dot-matrix screen from `OledScreens.kt`.
 */
@Composable
fun MiniLcd(state: DeckUiState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Metal.lcdBg),
    ) {
        val alpha = when (state.leds) {
            LedBrightness.LOW -> 0.4f
            LedBrightness.MEDIUM -> 0.75f
            LedBrightness.HIGH -> 1.0f
        }
        // The host sizes the box; Oled() centers the 64x32 integer grid inside whatever it gets.
        OledScreen(state, Modifier.fillMaxSize().alpha(alpha))
    }
}
