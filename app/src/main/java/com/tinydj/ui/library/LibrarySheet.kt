package com.tinydj.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tinydj.ui.theme.Accent
import com.tinydj.ui.theme.Body
import com.tinydj.ui.theme.Ink

/**
 * A de-branded display row for the functional LIBRARY picker. Pure display metadata over a track
 * already known to the engine; [index] is the engine list position the sheet reports back on pick.
 */
data class LibraryTrack(
    val index: Int,
    val title: String,
    val artist: String,
    val sampleRate: Int,
    val durationMs: Long,
    val stereo: Boolean,
)

/**
 * The functional FLAC/MP3 picker over the existing engine (SPEC: LIBRARY is the one real playback
 * path; MEMO/REC are intentionally empty props). Reached through the device's mode interaction.
 * A [ModalBottomSheet] listing [tracks] A–Z; tapping a row calls [onPick] with that track's engine
 * index, [currentIndex] marks the loaded track. Fully de-branded — wordmark only.
 *
 * Contract surface: `(visible, tracks, currentIndex, onPick, onDismiss, modifier)`. [onAdd] and
 * [onRemove] are optional editing affordances (defaulted no-ops) so the contract call form holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(
    visible: Boolean,
    tracks: List<LibraryTrack>,
    currentIndex: Int,
    onPick: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit = {},
    onRemove: (index: Int) -> Unit = {},
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Body,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LIBRARY",
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                TextButton(onClick = onAdd) {
                    Text("+ ADD", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                }
            }

            if (tracks.isEmpty()) {
                Text(
                    "no tracks yet — tap + add to load flac / mp3 files",
                    color = Ink,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn {
                    items(tracks, key = { it.index }) { track ->
                        TrackRow(
                            track = track,
                            selected = track.index == currentIndex,
                            onClick = { onPick(track.index) },
                            onRemove = { onRemove(track.index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: LibraryTrack,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Loaded-track marker (caret) keeps it monospace-terminal, not a Material selection chip.
        Text(
            text = if (selected) "▸" else " ",
            color = Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = "${track.sampleRate / 1000}k",
            color = Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = Ink,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${track.artist}  ·  ${fmt(track.durationMs)}  ·  ${if (track.stereo) "ST" else "MO"}",
                color = Ink.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "×",
            color = Ink.copy(alpha = 0.5f),
            fontSize = 20.sp,
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(start = 12.dp, end = 4.dp),
        )
    }
}

private fun fmt(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}
