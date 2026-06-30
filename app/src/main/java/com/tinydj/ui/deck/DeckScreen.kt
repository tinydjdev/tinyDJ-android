package com.tinydj.ui.deck

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinydj.ui.library.LibrarySheet
import com.tinydj.ui.library.LibraryTrack

/**
 * Hosts the [DeckViewModel], collects the [DeckUiState] snapshot, builds [DeckActions] from the
 * VM's state-machine methods, and renders the faithful device face via [DeckContent].
 *
 * The functional FLAC/MP3 picker ([LibrarySheet]) is opened through the **mode interaction**
 * (SPEC C.3 / C.4): the mode capsule's TAP cycles MEMO→REC→LIBRARY while stopped, and landing in
 * LIBRARY surfaces the track list so the user can load a file into the engine. The VM owns the
 * real mode/transport state; this screen only owns the transient visibility of the picker sheet.
 *
 * Everything on-screen is de-branded — wordmark `tinydj`/`TINYDJ`, no real brand/model tokens.
 */
@Composable
fun DeckScreen(vm: DeckViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showLibrary by remember { mutableStateOf(false) }

    // Storage Access Framework picker → import freshly chosen audio into the library repository.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.importUris(uris) }
    val launchPicker = { picker.launch(arrayOf("audio/*")) }

    val actions = remember(vm) {
        DeckActions(
            // -- transport --------------------------------------------------------------
            onPlay = vm::onPlay,
            onStop = vm::onStop,
            onStopHold = vm::onStopHold,
            onRecord = vm::onRecord,
            onRecordHold = vm::onRecordHold,

            // -- mode capsule (tri-modal) ----------------------------------------------
            // TAP cycles mode when stopped / opens varispeed when playing; if the tap leaves the
            // device in LIBRARY mode while stopped, raise the functional picker.
            onModeTap = {
                // Read live VM state (not the captured composition snapshot, which is stale
                // inside this remembered lambda).
                val before = vm.uiState.value
                // Only a "clean" mode-cycle tap on the root playback view should raise the picker;
                // any open overlay means this tap is dismissing the overlay instead (SPEC C.4).
                val rootView = before.oledView == OledView.PLAYBACK ||
                    before.oledView == OledView.NO_FILE
                vm.onModeTap()
                val after = vm.uiState.value
                val nowLibraryStopped = after.appMode == AppMode.LIBRARY && !after.isPlaying
                if (before.appMode != AppMode.LIBRARY && nowLibraryStopped && rootView) {
                    showLibrary = true
                }
            },
            onModeHold = vm::onModeHold,

            // -- file skip (PLUS / MINUS) ----------------------------------------------
            onPrev = vm::onPrev,
            onNext = vm::onNext,

            // -- side user buttons (UP / DOWN) -----------------------------------------
            onUserUp = vm::onUserUp,
            onUserUpHold = vm::onUserUpHold,
            onUserDown = vm::onUserDown,
            onUserDownHold = vm::onUserDownHold,

            // -- memo (inert capture prop) ---------------------------------------------
            onMemo = vm::onMemo,
            onMemoHold = vm::onMemoHold,

            // -- reel ------------------------------------------------------------------
            onReelRotate = vm::onReelRotate,
            onReelHoldStart = vm::onReelGrab,
            onReelHoldEnd = vm::onReelRelease,

            // -- rocker FF / REW scan --------------------------------------------------
            onScanStart = vm::onScanStart,
            onScanStop = vm::onScanStop,

            // -- varispeed (DeckActions delta is Int; VM accumulates in Float) ----------
            onSpeedAdjust = { delta -> vm.onSpeedAdjust(delta.toFloat()) },

            // -- volume / power --------------------------------------------------------
            onVolumeChange = vm::onVolumeChange,
            onVolumeSet = vm::onVolumeSet,
            onPowerToggle = vm::onPowerToggle,

            // -- system menu -----------------------------------------------------------
            onMenuScroll = vm::onMenuScroll,
            onMenuEnter = vm::onMenuEnter,
            onMenuBack = vm::onMenuBack,
            onMenuExit = vm::onMenuExit,
            onSettingChange = vm::onSettingChange,
            onSettingAdvanceField = vm::onSettingAdvanceField,

            // -- delete overlay --------------------------------------------------------
            onDeleteToggle = vm::onDeleteToggle,
            onDeleteConfirm = vm::onDeleteConfirm,

            // -- library ---------------------------------------------------------------
            onOpenLibrary = {
                vm.onOpenLibrary()
                showLibrary = true
            },
            onPickTrack = { index ->
                vm.onPickTrack(index)
                showLibrary = false
            },
            onTapA = vm::onTapA,
            onHoldA = vm::onHoldA,
            onTapB = vm::onTapB,
            onPressA = vm::onPressA,
            onCancelVibrate = vm::onCancelVibrate,
        )
    }

    DeckContent(state = state, actions = actions)

    // The picker maps the engine-side AudioTrack list into the de-branded LibraryTrack rows the
    // sheet renders, and reports the currently loaded track for the selection highlight.
    val tracks = state.library.mapIndexed { index, t ->
        LibraryTrack(
            index = index,
            title = t.title,
            artist = t.artist,
            sampleRate = state.fileSampleRate,
            durationMs = t.durationMs,
            stereo = true,
        )
    }
    val currentIndex = (state.trackNumber - 1).coerceAtLeast(-1)

    LibrarySheet(
        visible = showLibrary,
        tracks = tracks,
        currentIndex = currentIndex,
        onPick = { index ->
            vm.onPickTrack(index)
            showLibrary = false
        },
        onAdd = launchPicker,
        onRemove = { index -> state.library.getOrNull(index)?.let(vm::removeTrack) },
        onDismiss = { showLibrary = false },
    )
}
