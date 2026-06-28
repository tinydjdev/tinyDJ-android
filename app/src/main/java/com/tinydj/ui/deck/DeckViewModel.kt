package com.tinydj.ui.deck

import android.net.Uri
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tinydj.core.audio.AudioEngine
import com.tinydj.core.audio.AudioTrack
import com.tinydj.core.haptics.HapticEngine
import com.tinydj.core.haptics.HapticSpec
import com.tinydj.data.library.LibraryRepository
import com.tinydj.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.os.BatteryManager
import android.os.StatFs
import android.os.Environment
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.log10
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.IntentFilter
import android.content.BroadcastReceiver

// =====================================================================================
//  ENUMS  (PART C state machine + system-menu settings; see CONTRACT §1)
// =====================================================================================

enum class AppMode { MEMO, REC, LIBRARY }

enum class OledView {
    TITLE, PLAYBACK, NO_FILE, TIME_STATUS, BATTERY, MTP, UPGRADE,
    VARISPEED, VOLUME_WEDGE, FILE_INFO, MODE_BADGE,
    MENU_LIST, SETTING_EDIT, DELETE_CONFIRM, GAIN, EDIT_PROP
}

enum class TransportState {
    STOPPED_AT_START, PLAYING, PAUSED_REEL, PAUSED_STOP, SCRUBBING, FF, REW
}

enum class PlayDirection { FWD, REV }

/** Ordered exactly as the on-device system-menu list. */
enum class MenuItem {
    EDIT, PLAY, LEDS, VIB, SPK, SFX, MOTOR, MEMO,
    RATE, DISK, NAME, TIME, DATE, VER, RESET, BATT
}

// Settings value enums
enum class EditMode { REPLACE, MIX }
enum class PlayMode { RESUME, STOP, REPEAT }            // functional (track-end behavior)
enum class LedBrightness { LOW, MEDIUM, HIGH }          // functional
enum class SpkMode { ON, AUTO }                         // functional
enum class SfxMode { ON, OFF }
enum class MotorMode { OFF, PLAY, PLAY_REC }            // functional (gates the reel)
enum class MemoQuality { HI_Q, STD }
enum class SampleRateMode { R48, R96 }                  // display only

/**
 * Kept for the existing render test (it imports and sets [LcdMode.TIME]). LIBRARY
 * playback now renders through [OledView.PLAYBACK]; this enum survives only as the
 * legacy field the render test pins.
 */
enum class LcdMode { TIME, REMAINING, FRAMES }

// =====================================================================================
//  DeckUiState  (CONTRACT §2) — single immutable snapshot the face renders.
// =====================================================================================

data class DeckUiState(
    // ---- existing fields the render test sets (DO NOT remove/rename) ----
    val hasTrack: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionFrames: Long = 0L,
    val totalFrames: Long = 0L,
    val fileSampleRate: Int = 48000,
    val progress: Float = 0f,
    val speed: Float = 1f,             // engine rate = 1 + varispeed/100
    val volume: Float = 0.7f,
    val loop: Boolean = false,
    val lcdMode: LcdMode = LcdMode.TIME,

    // ---- mode / OLED view ----
    val appMode: AppMode = AppMode.LIBRARY,
    val oledView: OledView = OledView.PLAYBACK,
    val transport: TransportState = TransportState.STOPPED_AT_START,
    val direction: PlayDirection = PlayDirection.FWD,
    val powered: Boolean = true,
    val usbConnected: Boolean = false,

    // ---- varispeed ----
    val varispeed: Int = 0,            // -50..+200, 0 = detent
    val reversed: Boolean = false,     // mirror of direction == REV

    // ---- current track / list ----
    val trackNumber: Int = 1,          // 1-based; drives the inverted "01" box
    val trackCount: Int = 0,
    val dateLabel: String = "TODAY",   // bottom-left label (TODAY / JAN,1 / MIX / name)

    // ---- system menu ----
    val menuOpen: Boolean = false,
    val menuIndex: Int = 0,            // index into MenuItem.entries
    val settingOpen: Boolean = false,  // true => SETTING_EDIT page for current MenuItem
    val settingFieldIndex: Int = 0,    // active field on multi-field pages (TIME/DATE/NAME)

    // ---- settings values ----
    val editMode: EditMode = EditMode.MIX,
    val playMode: PlayMode = PlayMode.RESUME,
    val leds: LedBrightness = LedBrightness.MEDIUM,
    val vibrationStrength: Float = 1.0f,
    val spk: SpkMode = SpkMode.AUTO,
    val sfx: SfxMode = SfxMode.ON,
    val motor: MotorMode = MotorMode.PLAY_REC,
    val memoQuality: MemoQuality = MemoQuality.HI_Q,
    val sampleRateMode: SampleRateMode = SampleRateMode.R48,
    val diskFreeGb: Int = 100,
    val diskUsedGb: Int = 12,
    val diskTotalGb: Int = 128,
    val deviceName: String = "TINYDJ",   // NAME setting; de-branded
    val firmwareVersion: String = "0.1.0",

    // ---- overlays ----
    val fileInfoOpen: Boolean = false,
    val deleteOpen: Boolean = false,
    val deleteSelectYes: Boolean = false,
    val volumeOverlay: Boolean = false,

    // ---- device status ----
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val time: String = "00:00",        // HH:MM
    val date: String = "JAN01",        // display form
    val year: Int = 1980,
    val weekday: String = "TUE",

    // ---- inert capture props (drawn, never write audio) ----
    val recArmed: Boolean = false,
    val memoArmed: Boolean = false,
    val vuLeft: Float = 0f,            // 0..1 VU meter level
    val vuRight: Float = 0f,
    val gainDb: Int = 0,              // 0..42, inert

    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val currentScanningDir: String = "",
    val recentlyFound: List<String> = emptyList(),
    val scanError: String? = null,

    // ---- library backing list (functional playback path) ----
    val library: List<AudioTrack> = emptyList(),
)

// =====================================================================================
//  DeckActions  (CONTRACT §3) — every callback defaults so DeckActions() compiles.
// =====================================================================================

data class DeckActions(
    // transport
    val onPlay: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onStopHold: () -> Unit = {},
    val onRecord: () -> Unit = {},          // inert prop
    val onRecordHold: () -> Unit = {},      // inert prop
    // mode
    val onModeTap: () -> Unit = {},         // cycle mode (stopped) / open varispeed (playing)
    val onModeHold: () -> Unit = {},        // open system menu
    // file skip
    val onPrev: () -> Unit = {},            // MINUS
    val onNext: () -> Unit = {},            // PLUS
    // side user buttons
    val onUserUp: () -> Unit = {},          // gain prop / list-nav
    val onUserUpHold: () -> Unit = {},
    val onUserDown: () -> Unit = {},
    val onUserDownHold: () -> Unit = {},    // file info overlay
    // memo (inert)
    val onMemo: () -> Unit = {},
    val onMemoHold: () -> Unit = {},
    // reel
    val onReelRotate: (dirRight: Boolean, delta: Float) -> Unit = { _, _ -> },
    val onReelHoldStart: () -> Unit = {},
    val onReelHoldEnd: () -> Unit = {},
    // rocker FF/REW (scan)
    val onScanStart: (forward: Boolean) -> Unit = {},   // forward=true => FF, false => REW
    val onScanStop: () -> Unit = {},
    // varispeed
    val onSpeedAdjust: (delta: Int) -> Unit = {},
    // volume / power
    val onVolumeChange: (delta: Float) -> Unit = {},
    val onVolumeSet: (value: Float) -> Unit = {},
    val onPowerToggle: () -> Unit = {},
    // system menu
    val onMenuScroll: (dirRight: Boolean) -> Unit = {},
    val onMenuEnter: () -> Unit = {},       // PLUS in MENU_LIST
    val onMenuBack: () -> Unit = {},        // MINUS
    val onMenuExit: () -> Unit = {},        // MODE/STOP
    val onSettingChange: (dirRight: Boolean) -> Unit = {},
    val onSettingAdvanceField: () -> Unit = {},   // PLUS on SETTING_EDIT
    // delete overlay
    val onDeleteToggle: () -> Unit = {},
    val onDeleteConfirm: () -> Unit = {},
    // library
    val onOpenLibrary: () -> Unit = {},
    val onPickTrack: (index: Int) -> Unit = {},
)

// =====================================================================================
//  VM-owned interaction state the engine doesn't track.
// =====================================================================================

/**
 * Everything the [AudioEngine] can't represent: the higher-level transport phase, the
 * three app modes, varispeed, the live system-menu cursor and every setting value, plus
 * the transient overlays. The engine remains the source of truth for position/playing.
 */
private data class DeckLocal(
    val powered: Boolean = true,
    val booting: Boolean = false,
    val usbConnected: Boolean = false,

    val appMode: AppMode = AppMode.LIBRARY,
    val transport: TransportState = TransportState.STOPPED_AT_START,
    val direction: PlayDirection = PlayDirection.FWD,

    val varispeed: Int = 0,               // -50..+200
    val varispeedOpen: Boolean = false,

    // overlays (mutually exclusive in practice; the VM enforces it)
    val menuOpen: Boolean = false,
    val menuIndex: Int = 0,
    val settingOpen: Boolean = false,
    val settingFieldIndex: Int = 0,

    val fileInfoOpen: Boolean = false,
    val deleteOpen: Boolean = false,
    val deleteSelectYes: Boolean = false,
    val volumeOverlay: Boolean = false,
    val gainOpen: Boolean = false,
    val showTitle: Boolean = false,
    val resetConfirm: Int = 0,            // RESET rotate-to-confirm accumulator

    // settings values
    val editMode: EditMode = EditMode.MIX,
    val playMode: PlayMode = PlayMode.RESUME,
    val leds: LedBrightness = LedBrightness.MEDIUM,
    val vibrationStrength: Float = 1.0f,
    val spk: SpkMode = SpkMode.AUTO,
    val sfx: SfxMode = SfxMode.ON,
    val motor: MotorMode = MotorMode.PLAY_REC,
    val memoQuality: MemoQuality = MemoQuality.HI_Q,
    val sampleRateMode: SampleRateMode = SampleRateMode.R48,
    val deviceName: String = "TINYDJ",
    val timeHh: Int = 0,
    val timeMm: Int = 0,
    val dateDd: Int = 1,
    val dateMm: Int = 1,
    val dateYy: Int = 80,

    val gainDb: Int = 0,
    val recArmed: Boolean = false,
    val memoArmed: Boolean = false,
    val vuLeft: Float = 0f,
    val vuRight: Float = 0f,
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val currentScanningDir: String = "",
    val recentlyFound: List<String> = emptyList(),
    val scanError: String? = null,
)

/**
 * Owns the deck's complete interaction state machine (SPEC/CONTRACT PART C):
 *
 *  - **Transport** — PLAY starts forward, the next PLAY tap reverses; STOP is two-stage
 *    (freeze, then rewind); holding the reel momentarily pauses (the engine goes silent
 *    via [AudioEngine.beginScrub] with zero scrub speed); the rocker FF/REW-scans while
 *    held; PLUS/MINUS skip files.
 *  - **Mode** — the mode capsule is tri-modal: a TAP while stopped cycles MEMO→REC→
 *    LIBRARY, a TAP while playing opens/keeps VARISPEED, and a HOLD opens the SYSTEM MENU.
 *  - **System menu** — the full 17-item list with reel-scroll, PLUS=enter/advance-field,
 *    MINUS=back, MODE|STOP=exit, per-setting value pages and RESET rotate-to-confirm.
 *  - **Varispeed** — reel rotation sweeps −50..+200 % (engine rate 0.5..3.0) with a 0
 *    detent.
 *  - **Overlays** — DOWN-hold file info, STOP-hold delete, volume wedge.
 *  - **MOTOR gate** — OFF suppresses reel spin/scrub/varispeed-by-spin (finger still
 *    drives menu scroll & hold-pause).
 *
 * Crucially it owns the **per-unit value haptics**: every value adjust — varispeed, a
 * menu setting, volume, file selection, the NAME editor — emits EXACTLY ONE
 * [HapticSpec.ValueTick] per integer unit crossed (see [tickValue]). Six units ⇒ six
 * ticks. UI widgets feed raw movement here; this class converts it to discrete units so
 * every dial counts notches identically.
 *
 * Capture (RECORD/MEMO/EDIT/gain) is faithfully animated but inert — it never writes
 * audio. LIBRARY is the only functional engine path.
 */
class DeckViewModel(
    private val context: Context,
    private val engine: AudioEngine,
    private val rawHaptics: HapticEngine,
    private val library: LibraryRepository,
) : ViewModel() {

    private val haptics = object : HapticEngine {
        override val capabilities get() = rawHaptics.capabilities
        override var intensity: Float
            get() = rawHaptics.intensity
            set(value) { rawHaptics.intensity = value }
        
        override fun play(spec: HapticSpec) {
            if (local.value.sfx == SfxMode.ON) {
                rawHaptics.play(spec)
            }
        }
        
        override fun cancel() = rawHaptics.cancel()
        override fun shutdown() = rawHaptics.shutdown()
    }

    private val sharedPrefs = context.getSharedPreferences("tinydj_settings", Context.MODE_PRIVATE)

    private fun loadSettings(default: DeckLocal): DeckLocal {
        val savedStrength = sharedPrefs.getFloat("vibrationStrength", default.vibrationStrength)
        rawHaptics.intensity = savedStrength
        return default.copy(
            playMode = PlayMode.valueOf(sharedPrefs.getString("playMode", default.playMode.name) ?: default.playMode.name),
            leds = LedBrightness.valueOf(sharedPrefs.getString("leds", default.leds.name) ?: default.leds.name),
            sfx = SfxMode.valueOf(sharedPrefs.getString("sfx", default.sfx.name) ?: default.sfx.name),
            motor = MotorMode.valueOf(sharedPrefs.getString("motor", default.motor.name) ?: default.motor.name),
            memoQuality = MemoQuality.valueOf(sharedPrefs.getString("memoQuality", default.memoQuality.name) ?: default.memoQuality.name),
            sampleRateMode = SampleRateMode.valueOf(sharedPrefs.getString("sampleRateMode", default.sampleRateMode.name) ?: default.sampleRateMode.name),
            deviceName = sharedPrefs.getString("deviceName", default.deviceName) ?: default.deviceName,
            gainDb = sharedPrefs.getInt("gainDb", default.gainDb),
            vibrationStrength = savedStrength
        )
    }

    private fun saveSetting(key: String, value: Any) {
        val editor = sharedPrefs.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Enum<*> -> editor.putString(key, value.name)
        }
        editor.apply()
    }

    private val local = MutableStateFlow(loadSettings(DeckLocal()))

    // Reel-scrub bookkeeping (engine is source of truth for position/playing).
    private var wasPlayingBeforeScrub = false
    private var scanWasPlaying = false
    private var reelHeld = false
    private var lastRotateTimeNs = 0L

    // Sub-integer accumulators for value scrolling: a small drag delta becomes whole
    // units only when it crosses an integer boundary, and each crossing ticks once.
    private var varispeedAccum = 0f
    private var volumeTickAccum = 0f
    private var parameterAccum = 0f

    private var idleScrubJob: Job? = null
    private var bootJob: Job? = null
    private var overlayJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            local.update { it }
        }
    }

    init {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        engine.setLoop(local.value.playMode == PlayMode.REPEAT)
        // Boundary thump when the engine bumps the start or end of a file.
        viewModelScope.launch {
            var prevPlaying = false
            var prevProgress = 0f
            engine.state.collect { s ->
                if (prevPlaying && !s.isPlaying &&
                    (s.progress >= 0.999f || s.progress <= 0.001f) &&
                    prevProgress != s.progress
                ) {
                    onTrackEnd(s.progress >= 0.999f)
                }
                prevPlaying = s.isPlaying
                prevProgress = s.progress
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            autoScanExternalDirectories()
        }
    }

    // =================================================================================
    //  Derived UI state
    // =================================================================================

    val uiState: StateFlow<DeckUiState> =
        combine(engine.state, library.tracks, local) { s, tracks, loc ->
            val idx = tracks.indexOfFirst { it.id == s.trackId }
            val hasTrack = s.trackId != null
            val transport = deriveTransport(s.isPlaying, s.isScrubbing, loc)
            DeckUiState(
                hasTrack = hasTrack,
                title = tracks.getOrNull(idx)?.title ?: "",
                artist = tracks.getOrNull(idx)?.artist ?: "",
                isPlaying = s.isPlaying,
                positionMs = s.positionMs,
                durationMs = s.durationMs,
                positionFrames = s.positionFrames,
                totalFrames = s.totalFrames,
                fileSampleRate = s.fileSampleRate.takeIf { it > 0 } ?: 48_000,
                progress = s.progress,
                speed = s.speed,
                volume = s.volume,
                loop = s.loop,
                lcdMode = LcdMode.TIME,

                appMode = loc.appMode,
                oledView = deriveOledView(loc, hasTrack),
                transport = transport,
                direction = loc.direction,
                powered = loc.powered,
                usbConnected = loc.usbConnected,

                varispeed = loc.varispeed,
                reversed = loc.direction == PlayDirection.REV,

                trackNumber = if (idx >= 0) idx + 1 else 0,
                trackCount = tracks.size,
                dateLabel = if (hasTrack) (tracks.getOrNull(idx)?.title?.uppercase() ?: "MIX") else "TODAY",

                menuOpen = loc.menuOpen,
                menuIndex = loc.menuIndex,
                settingOpen = loc.settingOpen,
                settingFieldIndex = loc.settingFieldIndex,

                editMode = loc.editMode,
                playMode = loc.playMode,
                leds = loc.leds,
                vibrationStrength = loc.vibrationStrength,
                spk = loc.spk,
                sfx = loc.sfx,
                motor = loc.motor,
                memoQuality = loc.memoQuality,
                sampleRateMode = loc.sampleRateMode,
                deviceName = loc.deviceName,
                diskFreeGb = getDiskFreeGb(),
                diskUsedGb = getDiskUsedGb(),
                diskTotalGb = getDiskTotalGb(),

                fileInfoOpen = loc.fileInfoOpen,
                deleteOpen = loc.deleteOpen,
                deleteSelectYes = loc.deleteSelectYes,
                volumeOverlay = loc.volumeOverlay,

                time = "%02d:%02d".format(loc.timeHh, loc.timeMm),
                date = "%02d/%02d/%02d".format(loc.dateDd, loc.dateMm, loc.dateYy),

                recArmed = loc.recArmed,
                memoArmed = loc.memoArmed,
                vuLeft = if (loc.memoArmed || loc.recArmed) loc.vuLeft else s.vuLeft,
                vuRight = if (loc.memoArmed || loc.recArmed) loc.vuRight else s.vuRight,
                gainDb = loc.gainDb,

                isScanning = loc.isScanning,
                scannedCount = loc.scannedCount,
                totalCount = loc.totalCount,
                currentScanningDir = loc.currentScanningDir,
                recentlyFound = loc.recentlyFound,
                scanError = loc.scanError,

                batteryPct = getBatteryPct(),
                charging = isCharging(),

                library = tracks,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckUiState())

    private fun getBatteryPct(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
    }

    private fun isCharging(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getDiskFreeGb(): Int {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            (freeBytes / (1024 * 1024 * 1024)).toInt()
        } catch (e: Exception) {
            100
        }
    }

    private fun getDiskTotalGb(): Int {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            (totalBytes / (1024 * 1024 * 1024)).toInt()
        } catch (e: Exception) {
            128
        }
    }

    private fun getDiskUsedGb(): Int {
        val total = getDiskTotalGb()
        val free = getDiskFreeGb()
        return (total - free).coerceAtLeast(0)
    }

    private fun deriveTransport(playing: Boolean, scrubbing: Boolean, loc: DeckLocal): TransportState =
        when {
            scrubbing && reelHeld -> TransportState.PAUSED_REEL
            scrubbing -> loc.transport.takeIf { it == TransportState.FF || it == TransportState.REW }
                ?: TransportState.SCRUBBING
            playing -> TransportState.PLAYING
            loc.transport == TransportState.PAUSED_STOP -> TransportState.PAUSED_STOP
            else -> TransportState.STOPPED_AT_START
        }

    /** The single OLED screen the firmware shows, by overlay precedence. */
    private fun deriveOledView(loc: DeckLocal, hasTrack: Boolean): OledView = when {
        !loc.powered && loc.usbConnected -> OledView.MTP
        !loc.powered -> OledView.PLAYBACK            // (panel is dark; host draws blank)
        loc.showTitle -> OledView.TITLE
        loc.deleteOpen -> OledView.DELETE_CONFIRM
        loc.fileInfoOpen -> OledView.FILE_INFO
        loc.gainOpen -> OledView.GAIN
        loc.settingOpen -> OledView.SETTING_EDIT
        loc.menuOpen -> OledView.MENU_LIST
        loc.varispeedOpen -> OledView.VARISPEED
        loc.volumeOverlay -> OledView.VOLUME_WEDGE
        loc.appMode != AppMode.LIBRARY -> OledView.NO_FILE   // MEMO/REC are empty by design
        !hasTrack -> OledView.NO_FILE
        else -> OledView.PLAYBACK
    }

    // =================================================================================
    //  Per-unit value haptics (the explicit requirement)
    // =================================================================================

    /**
     * Fire EXACTLY ONE crisp tick per discrete unit crossed between [oldValue] and
     * [newValue]. A six-unit move buzzes six times. This is the single chokepoint all
     * value adjusts route through, so varispeed, menu settings, volume, file selection
     * and the NAME editor all feel identical. Capped only by the engine's ~60 Hz safety
     * limit (HapticThrottler.submitValueTick), never coalesced like a free scrub.
     */
    private fun tickValue(oldValue: Int, newValue: Int) {
        var ticks = abs(newValue - oldValue)
        if (ticks <= 0) return
        // Safety: never blast more than a sane burst from one event (e.g. a giant fling).
        if (ticks > 64) ticks = 64
        repeat(ticks) { haptics.play(HapticSpec.ValueTick) }
    }

    /** A softer single notch when a value snaps to a detent (e.g. varispeed 0). */
    private fun snapTick() = haptics.play(HapticSpec.SnapHome)

    // =================================================================================
    //  Power / boot  (C.2, C.10)
    // =================================================================================

    fun onPowerToggle() {
        if (local.value.powered) powerOff() else powerOn()
    }

    private fun powerOff() {
        engine.pause()
        haptics.play(HapticSpec.Pause)
        clearOverlays()
        local.update { it.copy(powered = false, showTitle = false) }
    }

    private fun powerOn() {
        haptics.play(HapticSpec.Play)
        local.update { it.copy(powered = true, showTitle = true, booting = true) }
        bootJob?.cancel()
        bootJob = viewModelScope.launch {
            delay(1200)                              // ~1.2s start-up title
            local.update { it.copy(showTitle = false, booting = false) }
        }
    }

    fun onUsbConnected(connected: Boolean) =
        local.update { it.copy(usbConnected = connected) }

    // =================================================================================
    //  Transport sub-machine  (C.5)
    // =================================================================================

    fun onTransportPress() = haptics.play(HapticSpec.ButtonPress)

    /** PLAY: start forward; tap again while playing reverses direction. */
    fun onPlay() {
        if (consumeOverlaysForTransport()) return
        val s = engine.state.value
        if (s.isPlaying) {
            val nextDir = if (local.value.direction == PlayDirection.FWD)
                PlayDirection.REV else PlayDirection.FWD
            local.update { it.copy(direction = nextDir, transport = TransportState.PLAYING) }
            haptics.play(HapticSpec.ModeToggle)
            applySpeed()
        } else {
            val startDir = if (s.progress >= 0.999f) PlayDirection.REV else PlayDirection.FWD
            local.update {
                it.copy(direction = startDir, transport = TransportState.PLAYING)
            }
            haptics.play(HapticSpec.Play)
            applySpeed()
            engine.play()
        }
    }

    /** STOP: first press freezes (PAUSED_STOP); second rewinds to start. */
    fun onStop() {
        if (consumeOverlaysForTransport()) return
        val s = engine.state.value
        if (s.isPlaying || local.value.transport == TransportState.SCRUBBING) {
            haptics.play(HapticSpec.Pause)
            engine.pause()
            local.update { it.copy(transport = TransportState.PAUSED_STOP) }
        } else if (local.value.transport == TransportState.PAUSED_STOP) {
            haptics.play(HapticSpec.Pause)
            engine.stop()
            local.update {
                it.copy(transport = TransportState.STOPPED_AT_START, direction = PlayDirection.FWD)
            }
        } else {
            // Already at start: a faithful no-op nudge.
            haptics.play(HapticSpec.ButtonPress)
            engine.stop()
            local.update { it.copy(transport = TransportState.STOPPED_AT_START) }
        }
    }

    /** STOP-hold opens the delete overlay (C.9). */
    fun onStopHold() {
        if (local.value.menuOpen || local.value.settingOpen) return
        haptics.play(HapticSpec.ReelGrab)
        clearOverlays()
        parameterAccum = 0f
        local.update { it.copy(deleteOpen = true, deleteSelectYes = false) }
    }

    // --- record / memo (inert capture props) ---

    fun onRecord() {
        val isRecording = local.value.recArmed
        if (isRecording) {
            stopSampleRecording()
        } else {
            startSampleRecording()
        }
    }

    fun onRecordHold() {
        haptics.play(HapticSpec.ReelGrab)
        if (!local.value.recArmed) {
            startSampleRecording()
        }
    }

    private fun startSampleRecording() {
        haptics.play(HapticSpec.ButtonPress)
        local.update { it.copy(recArmed = true) }
        
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val deviceRate = engine.getDeviceRate().coerceAtLeast(8000)
            val sampleRate = if (local.value.sampleRateMode == SampleRateMode.R96) 96000 else 48000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            
            // JNI output is always native rate. We will upsample to sampleRate if needed.
            val ratio = if (sampleRate > deviceRate) sampleRate / deviceRate else 1
            
            engine.startRecording()
            
            val tempFile = File(context.cacheDir, "temp_sample.pcm")
            val outStream = FileOutputStream(tempFile)
            
            var recorder: AudioRecord? = null
            
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(2048)
            val playerBuffer = ShortArray(minBufferSize)
            val micBuffer = ShortArray(minBufferSize)
            
            try {
                while (local.value.recArmed) {
                    // 1. Pull player audio from JNI
                    val readPlayer = engine.pullRecording(playerBuffer)
                    
                    // 2. Manage mic recorder based on memoArmed state
                    val micArmed = local.value.memoArmed
                    var readMic = 0
                    if (micArmed) {
                        if (recorder == null) {
                            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                recorder = try {
                                    AudioRecord(
                                        MediaRecorder.AudioSource.MIC,
                                        sampleRate,
                                        channelConfig,
                                        audioFormat,
                                        minBufferSize
                                    ).apply {
                                        if (state == AudioRecord.STATE_INITIALIZED) {
                                            startRecording()
                                        } else {
                                            release()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("DeckViewModel", "Failed to create mic AudioRecord for layering", e)
                                    null
                                }
                            }
                        }
                        if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
                            readMic = recorder.read(micBuffer, 0, micBuffer.size)
                        }
                    } else {
                        if (recorder != null) {
                            runCatching {
                                recorder.stop()
                                recorder.release()
                            }
                            recorder = null
                        }
                    }
                    
                    // 3. Resample player audio to match sampleRate if needed
                    val playerResampledSize = readPlayer * ratio
                    val playerResampled = if (ratio > 1 && readPlayer > 0) {
                        ShortArray(playerResampledSize).apply {
                            for (i in 0 until playerResampledSize) {
                                this[i] = playerBuffer[i / ratio]
                            }
                        }
                    } else {
                        playerBuffer
                    }
                    
                    // 4. Mix player and mic audio
                    val limit = maxOf(playerResampledSize, readMic)
                    if (limit > 0) {
                        val gainDb = local.value.gainDb
                        val linearGain = 10f.pow(gainDb / 20f)
                        
                        val mixedBuffer = ShortArray(limit)
                        var maxVal = 0f
                        for (i in 0 until limit) {
                            val pVal = if (i < playerResampledSize) playerResampled[i].toFloat() else 0f
                            val mVal = if (i < readMic) micBuffer[i].toFloat() * linearGain else 0f
                            
                            val mixed = pVal + mVal
                            val clamped = mixed.coerceIn(-32768f, 32767f)
                            mixedBuffer[i] = clamped.toInt().toShort()
                            
                            val absSample = abs(clamped) / 32768f
                            if (absSample > maxVal) {
                                maxVal = absSample
                            }
                        }
                        
                        // Convert to byte buffer and write
                        val byteBuffer = ByteArray(limit * 2)
                        for (i in 0 until limit) {
                            val s = mixedBuffer[i]
                            byteBuffer[i * 2] = (s.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((s.toInt() shr 8) and 0xff).toByte()
                        }
                        outStream.write(byteBuffer)
                        
                        val peak = maxVal.coerceIn(0f, 1f)
                        local.update { it.copy(vuLeft = peak, vuRight = peak) }
                    }
                    
                    delay(20)
                }
            } catch (e: Exception) {
                Log.e("DeckViewModel", "Sample recording loop exception", e)
            } finally {
                engine.stopRecording()
                if (recorder != null) {
                    runCatching {
                        recorder.stop()
                        recorder.release()
                    }
                    recorder = null
                }
                runCatching {
                    outStream.close()
                }
                audioRecord = null
                local.update { it.copy(vuLeft = 0f, vuRight = 0f) }
                
                // Reset memoArmed if we were layering
                local.update { it.copy(memoArmed = false) }
                
                if (tempFile.exists() && tempFile.length() > 0) {
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val dir = File(musicDir, "TinyDJ/samples")
                    if (!dir.exists()) dir.mkdirs()
                    val extension = if (local.value.memoQuality == MemoQuality.HI_Q) "wav" else "mp3"
                    val sampleFile = File(dir, "SAMPLE_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$extension")
                    
                    FileOutputStream(sampleFile).use { finalOut ->
                        writeWavHeader(finalOut, 1, sampleRate, 16, tempFile.length())
                        tempFile.inputStream().use { input ->
                            input.copyTo(finalOut)
                        }
                    }
                    tempFile.delete()
                    
                    library.import(listOf(Uri.fromFile(sampleFile)))
                }
            }
        }
    }

    private fun stopSampleRecording() {
        haptics.play(HapticSpec.ButtonRelease)
        local.update { it.copy(recArmed = false) }
    }

    private fun autoScanExternalDirectories() {
        try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val tinyDjDir = File(musicDir, "TinyDJ")
            val memosDir = File(tinyDjDir, "memos")
            val samplesDir = File(tinyDjDir, "samples")
            
            val filesToImport = mutableListOf<File>()
            listOf(memosDir, samplesDir).forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        if (file.isFile && (file.extension.equals("wav", ignoreCase = true) || file.extension.equals("mp3", ignoreCase = true))) {
                            filesToImport.add(file)
                        }
                    }
                }
            }
            if (filesToImport.isNotEmpty()) {
                val uris = filesToImport.map { Uri.fromFile(it) }
                viewModelScope.launch {
                    library.import(uris)
                }
            }
        } catch (e: Exception) {
            Log.e("DeckViewModel", "Failed to auto-scan external directories", e)
        }
    }

    fun onMemo() {
        val isSampleRecording = local.value.recArmed
        if (isSampleRecording) {
            val nextMemoArmed = !local.value.memoArmed
            local.update { it.copy(memoArmed = nextMemoArmed) }
            haptics.play(if (nextMemoArmed) HapticSpec.ButtonPress else HapticSpec.ButtonRelease)
        } else {
            val isRecording = local.value.memoArmed
            if (isRecording) {
                stopMemoRecording()
            } else {
                startMemoRecording()
            }
        }
    }

    private fun startMemoRecording() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("DeckViewModel", "RECORD_AUDIO permission not granted")
            return
        }
        haptics.play(HapticSpec.ButtonPress)
        local.update { it.copy(memoArmed = true) }
        
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val sampleRate = if (local.value.sampleRateMode == SampleRateMode.R96) 96000 else 48000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(2048)
            
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize
                )
            } catch (e: Exception) {
                Log.e("DeckViewModel", "Failed to create AudioRecord", e)
                local.update { it.copy(memoArmed = false) }
                return@launch
            }
            
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("DeckViewModel", "AudioRecord not initialized")
                recorder.release()
                local.update { it.copy(memoArmed = false) }
                return@launch
            }
            
            val tempFile = File(context.cacheDir, "temp_memo.pcm")
            val outStream = FileOutputStream(tempFile)
            val buffer = ShortArray(minBufferSize)
            
            try {
                recorder.startRecording()
                audioRecord = recorder
                
                while (local.value.memoArmed) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val gainDb = local.value.gainDb
                        val linearGain = 10f.pow(gainDb / 20f)
                        
                        var maxVal = 0f
                        for (i in 0 until read) {
                            val sample = buffer[i].toFloat() * linearGain
                            val clamped = sample.coerceIn(-32768f, 32767f)
                            buffer[i] = clamped.toInt().toShort()
                            
                            val absSample = abs(clamped) / 32768f
                            if (absSample > maxVal) {
                                maxVal = absSample
                            }
                        }
                        
                        val byteBuffer = ByteArray(read * 2)
                        for (i in 0 until read) {
                            val s = buffer[i]
                            byteBuffer[i * 2] = (s.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((s.toInt() shr 8) and 0xff).toByte()
                        }
                        outStream.write(byteBuffer)
                        
                        val peak = maxVal.coerceIn(0f, 1f)
                        local.update { it.copy(vuLeft = peak, vuRight = peak) }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeckViewModel", "Recording loop exception", e)
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
                runCatching {
                    outStream.close()
                }
                audioRecord = null
                local.update { it.copy(vuLeft = 0f, vuRight = 0f) }
                
                if (tempFile.exists() && tempFile.length() > 0) {
                    val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    val dir = File(musicDir, "TinyDJ/memos")
                    if (!dir.exists()) dir.mkdirs()
                    val extension = if (local.value.memoQuality == MemoQuality.HI_Q) "wav" else "mp3"
                    val memoFile = File(dir, "MEMO_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$extension")
                    
                    FileOutputStream(memoFile).use { finalOut ->
                        writeWavHeader(finalOut, 1, sampleRate, 16, tempFile.length())
                        tempFile.inputStream().use { input ->
                            input.copyTo(finalOut)
                        }
                    }
                    tempFile.delete()
                    
                    library.import(listOf(Uri.fromFile(memoFile)))
                }
            }
        }
    }

    private fun stopMemoRecording() {
        haptics.play(HapticSpec.ButtonRelease)
        local.update { it.copy(memoArmed = false) }
    }

    private fun writeWavHeader(out: OutputStream, channels: Int, sampleRate: Int, bitsPerSample: Int, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }

    fun onMemoHold() = haptics.play(HapticSpec.ReelGrab)

    // =================================================================================
    //  Mode capsule — tri-modal  (C.3, C.4)
    // =================================================================================

    /**
     * TAP while playing → open/keep VARISPEED; TAP while stopped/paused → cycle app mode.
     * Inside a menu/overlay a tap exits it.
     */
    fun onModeTap() {
        val loc = local.value
        if (loc.menuOpen || loc.settingOpen) { closeMenu(); return }
        if (loc.varispeedOpen) { closeVarispeed(); return }
        if (loc.deleteOpen || loc.fileInfoOpen || loc.gainOpen) { clearOverlays(); return }

        if (engine.state.value.isPlaying) {
            openVarispeed()
        } else {
            cycleMode()
        }
    }

    /** HOLD → open the system menu (C.7). */
    fun onModeHold() {
        haptics.play(HapticSpec.ModeToggle)
        clearOverlays()
        parameterAccum = 0f
        local.update {
            it.copy(menuOpen = true, settingOpen = false, settingFieldIndex = 0)
        }
    }

    private fun cycleMode() {
        val next = when (local.value.appMode) {
            AppMode.MEMO -> AppMode.REC
            AppMode.REC -> AppMode.LIBRARY
            AppMode.LIBRARY -> AppMode.MEMO
        }
        haptics.play(HapticSpec.ModeToggle)
        local.update { it.copy(appMode = next) }
    }

    // =================================================================================
    //  Varispeed  (C.6)
    // =================================================================================

    private fun openVarispeed() {
        haptics.play(HapticSpec.ModeToggle)
        varispeedAccum = 0f
        local.update { it.copy(varispeedOpen = true) }
    }

    private fun closeVarispeed() {
        haptics.play(HapticSpec.ModeToggle)
        local.update { it.copy(varispeedOpen = false) }   // speedPct persists
    }

    /**
     * Reel/knob movement while varispeed is open. [delta] is a raw drag in the widget's
     * units; we accumulate it into whole percent units and tick once per unit crossed.
     * Range −50..+200, with a haptic detent exactly at 0.
     */
    fun onSpeedAdjust(delta: Float) {
        if (!local.value.varispeedOpen) return
        varispeedAccum += delta * SPEED_UNITS_PER_PX
        val whole = varispeedAccum.toInt()
        if (whole == 0) return
        varispeedAccum -= whole
        val old = local.value.varispeed
        val next = (old + whole).coerceIn(VARISPEED_MIN, VARISPEED_MAX)
        if (next == old) return
        // One tick per percent crossed; a deeper snap when we land on the 0 detent.
        if (old != 0 && next == 0) {
            tickValue(old, -1)   // tick up to just before 0…
            snapTick()           // …then the distinct centre detent
        } else {
            tickValue(old, next)
        }
        local.update { it.copy(varispeed = next) }
        applySpeed()
    }

    /** Effective signed engine rate = direction × (1 + varispeed/100), clamped 0.5..3.0. */
    private fun applySpeed() {
        val loc = local.value
        val mag = (1f + loc.varispeed / 100f).coerceIn(0.5f, 3.0f)
        engine.setSpeed(if (loc.direction == PlayDirection.REV) -mag else mag)
    }

    // =================================================================================
    //  File skip  (C.5)
    // =================================================================================

    fun onNext() = skip(+1)
    fun onPrev() = skip(-1)

    private fun skip(dir: Int) {
        // PLUS/MINUS are repurposed inside menu/overlays.
        if (routeSkipToOverlay(dir)) return
        val tracks = library.tracks.value
        if (tracks.isEmpty()) { haptics.play(HapticSpec.ButtonPress); return }
        haptics.play(HapticSpec.ValueTick)        // one notch per file step
        val curIdx = tracks.indexOfFirst { it.id == engine.state.value.trackId }
        val n = tracks.size
        // Clamp at the ends (faithful: no wrap on a single tap).
        val nextIdx = when {
            curIdx < 0 -> if (dir > 0) 0 else n - 1
            else -> (curIdx + dir).coerceIn(0, n - 1)
        }
        if (nextIdx == curIdx) return
        local.update {
            it.copy(direction = PlayDirection.FWD, transport = TransportState.STOPPED_AT_START)
        }
        engine.load(tracks[nextIdx])
    }

    // =================================================================================
    //  Side user buttons (UP/DOWN)  (C.4 #3)
    // =================================================================================

    /** UP tap: gain prop (inert) when not browsing; +1 gain unit, one tick. */
    fun onUserUp() {
        if (local.value.menuOpen || local.value.settingOpen) { onMenuScroll(false); return }
        val old = local.value.gainDb
        val next = (old + 1).coerceIn(0, 42)
        tickValue(old, next)
        local.update { it.copy(gainDb = next, gainOpen = true) }
        popGainOverlay()
    }

    fun onUserUpHold() = onUserUp()

    /** DOWN tap: gain prop down; DOWN-hold = file info overlay. */
    fun onUserDown() {
        if (local.value.menuOpen || local.value.settingOpen) { onMenuScroll(true); return }
        val old = local.value.gainDb
        val next = (old - 1).coerceIn(0, 42)
        tickValue(old, next)
        local.update { it.copy(gainDb = next, gainOpen = true) }
        popGainOverlay()
    }

    fun onUserDownHold() {
        haptics.play(HapticSpec.ButtonPress)
        clearOverlays()
        local.update { it.copy(fileInfoOpen = true) }
    }

    private fun popGainOverlay() {
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(1100)
            local.update { it.copy(gainOpen = false) }
        }
    }

    // =================================================================================
    //  Reel — scrub / hold-pause / menu-scroll / varispeed  (C.4 #4, C.5, C.8)
    // =================================================================================

    fun onReelGrab() {
        reelHeld = true
        lastRotateTimeNs = System.nanoTime()
        val loc = local.value
        if (loc.menuOpen || loc.settingOpen || loc.varispeedOpen || loc.deleteOpen) {
            haptics.play(HapticSpec.ReelGrab)
            return
        }
        wasPlayingBeforeScrub = engine.state.value.isPlaying
        // MOTOR off → finger still registers (for menu/pause) but does not scrub audio.
        if (motorEnabled()) {
            engine.beginScrub()             // engine goes silent until a scrub speed arrives
        }
        haptics.play(HapticSpec.ReelGrab)
        // A momentary hold while playing = PAUSED_REEL (silence) until release (C.5).
        if (wasPlayingBeforeScrub && !motorEnabled()) {
            engine.beginScrub()             // silence while held even with MOTOR off
        }
        local.update { it.copy(transport = TransportState.PAUSED_REEL) }
    }

    /**
     * Raw reel rotation. Routed by context: menu scroll, varispeed, delete toggle, reset
     * confirm, or audio scrub. [dirRight] is CW; [delta] is the gesture magnitude.
     */
    fun onReelRotate(dirRight: Boolean, delta: Float) {
        val loc = local.value
        when {
            loc.settingOpen -> {
                val signedDelta = if (dirRight) delta else -delta
                parameterAccum += signedDelta
                val steps = (parameterAccum / SETTING_STEP_DEG).toInt()
                if (steps != 0) {
                    parameterAccum -= steps * SETTING_STEP_DEG
                    repeat(abs(steps)) {
                        onSettingChange(steps > 0)
                    }
                }
            }
            loc.menuOpen -> {
                val signedDelta = if (dirRight) delta else -delta
                parameterAccum += signedDelta
                val steps = (parameterAccum / MENU_STEP_DEG).toInt()
                if (steps != 0) {
                    parameterAccum -= steps * MENU_STEP_DEG
                    repeat(abs(steps)) {
                        onMenuScroll(steps > 0)
                    }
                }
            }
            loc.deleteOpen -> {
                val signedDelta = if (dirRight) delta else -delta
                parameterAccum += signedDelta
                val steps = (parameterAccum / DELETE_STEP_DEG).toInt()
                if (steps != 0) {
                    parameterAccum -= steps * DELETE_STEP_DEG
                    repeat(abs(steps)) {
                        onDeleteToggle()
                    }
                }
            }
            loc.varispeedOpen -> onSpeedAdjust(if (dirRight) abs(delta) else -abs(delta))
            else -> scrubAudio(dirRight, delta)
        }
    }

    private fun scrubAudio(dirRight: Boolean, delta: Float) {
        if (!motorEnabled()) return                 // MOTOR off suppresses scratch (C.8)
        local.update { it.copy(transport = TransportState.SCRUBBING) }
        
        val now = System.nanoTime()
        val elapsedNs = now - lastRotateTimeNs
        lastRotateTimeNs = now

        val dt = if (elapsedNs in 1_000_000L..100_000_000L) {
            elapsedNs.toFloat() / 1_000_000_000f
        } else {
            0.016f // fallback 16ms
        }

        // Calculate speed based on 1.8 seconds per revolution (200 degrees/sec = 1.0x speed)
        val rawSpeed = delta / (200f * dt)
        val speed = (if (dirRight) 1f else -1f) * rawSpeed.coerceAtMost(8f)

        engine.setScrubSpeed(speed)
        // Free-scrub rim detents: rate-limited (HapticThrottler coalesces the smear).
        haptics.play(HapticSpec.Detent(abs(speed).coerceIn(0f, 1f)))
        idleScrubJob?.cancel()
        idleScrubJob = viewModelScope.launch {
            delay(150)                              // idle → fall back to prior state
            if (!reelHeld) {
                engine.endScrub(wasPlayingBeforeScrub)
                local.update {
                    it.copy(
                        transport = if (wasPlayingBeforeScrub) TransportState.PLAYING
                        else TransportState.PAUSED_STOP,
                    )
                }
            } else {
                // Finger still held but motionless -> set speed to 0 (silence)
                engine.setScrubSpeed(0f)
            }
        }
    }

    fun onReelRelease() {
        reelHeld = false
        haptics.play(HapticSpec.ReelRelease)
        // Inside menu/overlay the reel is a dial, not a scrubber — nothing to end.
        val loc = local.value
        if (loc.menuOpen || loc.settingOpen || loc.varispeedOpen || loc.deleteOpen) return
        engine.endScrub(wasPlayingBeforeScrub)
        local.update {
            it.copy(
                transport = if (wasPlayingBeforeScrub) TransportState.PLAYING
                else TransportState.PAUSED_STOP,
            )
        }
    }

    private fun motorEnabled() = local.value.motor != MotorMode.OFF

    // =================================================================================
    //  Side rocker — FF / REW scan  (C.5)
    // =================================================================================

    fun onScanStart(forward: Boolean) {
        if (!motorEnabled()) return
        scanWasPlaying = engine.state.value.isPlaying
        haptics.play(HapticSpec.ButtonPress)
        engine.beginScrub()
        engine.setScrubSpeed(if (forward) 4f else -4f)
        local.update {
            it.copy(transport = if (forward) TransportState.FF else TransportState.REW)
        }
    }

    fun onScanStop() {
        if (!motorEnabled()) return
        engine.endScrub(scanWasPlaying)
        haptics.play(HapticSpec.ButtonRelease)
        local.update {
            it.copy(
                transport = if (scanWasPlaying) TransportState.PLAYING
                else TransportState.PAUSED_STOP,
            )
        }
    }

    // =================================================================================
    //  Volume crown  (C.10) — per-unit detents on the 0..100 readout
    // =================================================================================

    fun onVolumeChange(delta: Float) {
        val old = engine.state.value.volume
        val next = (old + delta).coerceIn(0f, 1f)
        if (next == old) return
        engine.setVolume(next)
        emitVolumeTicks(old, next)
        popVolumeOverlay()
    }

    fun onVolumeSet(value: Float) {
        val old = engine.state.value.volume
        val next = value.coerceIn(0f, 1f)
        engine.setVolume(next)
        emitVolumeTicks(old, next)
        popVolumeOverlay()
    }

    /** One tick per integer percentage point crossed on the volume readout. */
    private fun emitVolumeTicks(oldV: Float, newV: Float) =
        tickValue((oldV * 100f).roundToInt(), (newV * 100f).roundToInt())

    private fun popVolumeOverlay() {
        local.update { it.copy(volumeOverlay = true) }
        overlayJob?.cancel()
        overlayJob = viewModelScope.launch {
            delay(900)
            local.update { it.copy(volumeOverlay = false) }
        }
    }

    // =================================================================================
    //  System menu navigation  (C.7)
    // =================================================================================

    fun onMenuScroll(dirRight: Boolean) {
        val items = MenuItem.entries
        val old = local.value.menuIndex
        val next = (old + if (dirRight) 1 else -1).coerceIn(0, items.size - 1)
        if (next == old) return
        tickValue(old, next)                      // one notch per row crossed
        local.update { it.copy(menuIndex = next) }
    }

    /** PLUS: enter the selected setting page, or advance a field when already editing. */
    fun onMenuEnter() {
        val loc = local.value
        if (loc.settingOpen) { onSettingAdvanceField(); return }
        if (!loc.menuOpen) return
        haptics.play(HapticSpec.ButtonPress)
        parameterAccum = 0f
        local.update { it.copy(settingOpen = true, settingFieldIndex = 0, resetConfirm = 0) }
    }

    /** MINUS: step back a field, then back to the list, then close. */
    fun onMenuBack() {
        val loc = local.value
        parameterAccum = 0f
        when {
            loc.settingOpen && loc.settingFieldIndex > 0 -> {
                haptics.play(HapticSpec.ButtonPress)
                local.update { it.copy(settingFieldIndex = it.settingFieldIndex - 1) }
            }
            loc.settingOpen -> {
                haptics.play(HapticSpec.ButtonPress)
                local.update { it.copy(settingOpen = false) }
            }
            loc.menuOpen -> closeMenu()            // MINUS in the list exits to playback
        }
    }

    fun onMenuExit() = closeMenu()

    private fun closeMenu() {
        haptics.play(HapticSpec.ModeToggle)
        parameterAccum = 0f
        local.update { it.copy(menuOpen = false, settingOpen = false, settingFieldIndex = 0) }
    }

    /** Reel turns the active setting's value; one tick per discrete change. */
    fun onSettingChange(dirRight: Boolean) {
        val loc = local.value
        val item = MenuItem.entries[loc.menuIndex]
        haptics.play(HapticSpec.ValueTick)
        local.update {
            val next = changeSetting(it, item, dirRight)
            when (item) {
                MenuItem.EDIT -> saveSetting("editMode", next.editMode)
                MenuItem.PLAY -> saveSetting("playMode", next.playMode)
                MenuItem.LEDS -> saveSetting("leds", next.leds)
                MenuItem.VIB -> saveSetting("vibrationStrength", next.vibrationStrength)
                MenuItem.SPK -> saveSetting("spk", next.spk)
                MenuItem.SFX -> saveSetting("sfx", next.sfx)
                MenuItem.MOTOR -> saveSetting("motor", next.motor)
                MenuItem.MEMO -> saveSetting("memoQuality", next.memoQuality)
                MenuItem.RATE -> saveSetting("sampleRateMode", next.sampleRateMode)
                MenuItem.NAME -> saveSetting("deviceName", next.deviceName)
                MenuItem.TIME -> { saveSetting("timeHh", next.timeHh); saveSetting("timeMm", next.timeMm) }
                MenuItem.DATE -> { saveSetting("dateDd", next.dateDd); saveSetting("dateMm", next.dateMm); saveSetting("dateYy", next.dateYy) }
                else -> {}
            }
            next
        }
    }

    private fun changeSetting(loc: DeckLocal, item: MenuItem, up: Boolean): DeckLocal {
        fun <T : Enum<T>> step(cur: T, values: Array<T>): T {
            val n = values.size
            val i = ((cur.ordinal + (if (up) 1 else -1)) % n + n) % n
            return values[i]
        }
        return when (item) {
            MenuItem.EDIT -> loc.copy(editMode = step(loc.editMode, EditMode.entries.toTypedArray()))
            MenuItem.PLAY -> {
                val m = step(loc.playMode, PlayMode.entries.toTypedArray())
                engine.setLoop(m == PlayMode.REPEAT)       // functional: track-end behavior
                loc.copy(playMode = m)
            }
            MenuItem.LEDS -> loc.copy(leds = step(loc.leds, LedBrightness.entries.toTypedArray()))
            MenuItem.VIB -> {
                val nextVal = (loc.vibrationStrength + if (up) 0.1f else -0.1f).coerceIn(0f, 1f)
                val rounded = (nextVal * 10f).roundToInt() / 10f
                rawHaptics.intensity = rounded
                loc.copy(vibrationStrength = rounded)
            }
            MenuItem.SPK -> loc.copy(spk = step(loc.spk, SpkMode.entries.toTypedArray()))
            MenuItem.SFX -> loc.copy(sfx = step(loc.sfx, SfxMode.entries.toTypedArray()))
            MenuItem.MOTOR -> loc.copy(motor = step(loc.motor, MotorMode.entries.toTypedArray()))
            MenuItem.MEMO -> loc.copy(memoQuality = step(loc.memoQuality, MemoQuality.entries.toTypedArray()))
            MenuItem.RATE -> loc.copy(sampleRateMode = step(loc.sampleRateMode, SampleRateMode.entries.toTypedArray()))
            MenuItem.NAME -> editName(loc, up)
            MenuItem.TIME -> editTime(loc, up)
            MenuItem.DATE -> editDate(loc, up)
            MenuItem.RESET -> {
                val c = loc.resetConfirm + 1               // rotate-to-confirm accumulator
                if (c >= RESET_TURNS) { performReset(); loc.copy(resetConfirm = 0, settingOpen = false, menuOpen = false) }
                else loc.copy(resetConfirm = c)
            }
            // DISK / VER / BATT are read-outs — reel does nothing.
            MenuItem.DISK, MenuItem.VER, MenuItem.BATT -> loc
        }
    }

    /** PLUS on a setting page advances the active field; multi-field commits stay open. */
    fun onSettingAdvanceField() {
        val loc = local.value
        val item = MenuItem.entries[loc.menuIndex]
        val fieldCount = when (item) {
            MenuItem.NAME -> 4
            MenuItem.TIME -> 2
            MenuItem.DATE -> 3
            else -> 1
        }
        haptics.play(HapticSpec.ButtonPress)
        parameterAccum = 0f
        if (loc.settingFieldIndex < fieldCount - 1) {
            local.update { it.copy(settingFieldIndex = it.settingFieldIndex + 1) }
        } else {
            // Last field → commit and return to the list.
            local.update { it.copy(settingOpen = false, settingFieldIndex = 0) }
        }
    }

    // --- multi-field editors (each value change still ticks once via onSettingChange) ---

    private fun editName(loc: DeckLocal, up: Boolean): DeckLocal {
        val chars = loc.deviceName.padEnd(4, ' ').take(4).toCharArray()
        val i = loc.settingFieldIndex.coerceIn(0, 3)
        chars[i] = cycleChar(chars[i], up)
        return loc.copy(deviceName = String(chars))
    }

    private fun cycleChar(c: Char, up: Boolean): Char {
        // Cycle space → A..Z → 0..9 → space, one step per tick.
        val alphabet = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val idx = alphabet.indexOf(c.uppercaseChar()).let { if (it < 0) 0 else it }
        val n = alphabet.length
        return alphabet[((idx + if (up) 1 else -1) % n + n) % n]
    }

    private fun editTime(loc: DeckLocal, up: Boolean): DeckLocal {
        val d = if (up) 1 else -1
        return if (loc.settingFieldIndex == 0) {
            loc.copy(timeHh = ((loc.timeHh + d) % 24 + 24) % 24)
        } else {
            loc.copy(timeMm = ((loc.timeMm + d) % 60 + 60) % 60)
        }
    }

    private fun editDate(loc: DeckLocal, up: Boolean): DeckLocal {
        val d = if (up) 1 else -1
        return when (loc.settingFieldIndex) {
            0 -> loc.copy(dateDd = ((loc.dateDd - 1 + d) % 31 + 31) % 31 + 1)
            1 -> loc.copy(dateMm = ((loc.dateMm - 1 + d) % 12 + 12) % 12 + 1)
            else -> loc.copy(dateYy = ((loc.dateYy + d) % 100 + 100) % 100)
        }
    }

    /** RESET clears the library and restores default settings. */
    private fun performReset() {
        haptics.play(HapticSpec.Boundary)
        viewModelScope.launch {
            library.tracks.value.forEach { library.remove(it) }
        }
        engine.stop()
        local.update {
            DeckLocal(powered = it.powered, usbConnected = it.usbConnected)
        }
    }

    // =================================================================================
    //  Delete overlay  (C.9)
    // =================================================================================

    fun onDeleteToggle() {
        haptics.play(HapticSpec.ValueTick)
        local.update { it.copy(deleteSelectYes = !it.deleteSelectYes) }
    }

    fun onDeleteConfirm() {
        val loc = local.value
        if (!loc.deleteOpen) return
        haptics.play(HapticSpec.ButtonPress)
        if (loc.deleteSelectYes) {
            // YES is valid only for MEMO/REC (which are empty ⇒ no-op). LIBRARY delete is
            // MTP-only on the device, so the prompt declines here.
            // (No destructive action — faithful.)
        }
        local.update { it.copy(deleteOpen = false, deleteSelectYes = false) }
    }

    // =================================================================================
    //  Library (functional)
    // =================================================================================

    fun onOpenLibrary() {
        haptics.play(HapticSpec.ButtonPress)
        local.update { it.copy(appMode = AppMode.LIBRARY) }
    }

    fun onPickTrack(index: Int) {
        val tracks = library.tracks.value
        val track = tracks.getOrNull(index) ?: return
        selectTrack(track)
    }

    fun selectTrack(track: AudioTrack) {
        haptics.play(HapticSpec.ButtonPress)
        local.update {
            it.copy(
                direction = PlayDirection.FWD,
                transport = TransportState.STOPPED_AT_START,
                appMode = AppMode.LIBRARY
            )
        }
        engine.load(track)
    }

    fun importUris(uris: List<Uri>) {
        viewModelScope.launch {
            library.import(uris)
        }
    }

    fun removeTrack(track: AudioTrack) {
        viewModelScope.launch { library.remove(track) }
    }

    fun renameMemo(track: AudioTrack, newTitle: String) {
        viewModelScope.launch {
            val oldId = track.id
            val newUri = library.renameTrack(track, newTitle)
            if (newUri != null) {
                engine.updateTrackId(oldId, newUri.toString())
            }
        }
    }

    // =================================================================================
    //  Public Setting Setters (for Settings tab)
    // =================================================================================
    fun setEditMode(mode: EditMode) {
        local.update { it.copy(editMode = mode) }
        saveSetting("editMode", mode)
    }
    fun setPlayMode(mode: PlayMode) {
        engine.setLoop(mode == PlayMode.REPEAT)
        local.update { it.copy(playMode = mode) }
        saveSetting("playMode", mode)
    }
    fun setLedBrightness(brightness: LedBrightness) {
        local.update { it.copy(leds = brightness) }
        saveSetting("leds", brightness)
    }
    fun setVibrationStrength(strength: Float) {
        val clamped = strength.coerceIn(0f, 1f)
        rawHaptics.intensity = clamped
        local.update { it.copy(vibrationStrength = clamped) }
        saveSetting("vibrationStrength", clamped)
    }
    fun setSpkMode(mode: SpkMode) {
        local.update { it.copy(spk = mode) }
        saveSetting("spk", mode)
    }
    fun setSfxMode(mode: SfxMode) {
        local.update { it.copy(sfx = mode) }
        saveSetting("sfx", mode)
    }
    fun setMotorMode(mode: MotorMode) {
        local.update { it.copy(motor = mode) }
        saveSetting("motor", mode)
    }
    fun setMemoQuality(quality: MemoQuality) {
        local.update { it.copy(memoQuality = quality) }
        saveSetting("memoQuality", quality)
    }
    fun setSampleRateMode(mode: SampleRateMode) {
        local.update { it.copy(sampleRateMode = mode) }
        saveSetting("sampleRateMode", mode)
    }
    fun setDeviceName(name: String) {
        val limited = name.take(8)
        local.update { it.copy(deviceName = limited) }
        saveSetting("deviceName", limited)
    }

    // =================================================================================
    //  Folder Scanning (survives lifecycle/recreation)
    // =================================================================================
    fun scanDirectoryUri(context: Context, treeUri: Uri) {
        viewModelScope.launch {
            local.update {
                it.copy(
                    isScanning = true,
                    scannedCount = 0,
                    totalCount = 0,
                    currentScanningDir = "Initializing...",
                    recentlyFound = emptyList(),
                    scanError = null
                )
            }
            try {
                // Take persistable permission for the tree itself
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                val uris = withContext(Dispatchers.IO) {
                    scanDirectoryHelper(context, treeUri) { count, dirName, fileName ->
                        local.update { loc ->
                            val updatedRecently = if (fileName != null) {
                                (listOf(fileName) + loc.recentlyFound).take(5)
                            } else {
                                loc.recentlyFound
                            }
                            loc.copy(
                                scannedCount = count,
                                currentScanningDir = dirName,
                                recentlyFound = updatedRecently
                            )
                        }
                    }
                }

                if (uris.isNotEmpty()) {
                    local.update {
                        it.copy(
                            scannedCount = 0,
                            totalCount = uris.size,
                            currentScanningDir = "Organizing..."
                        )
                    }
                    library.import(uris) { imported, total ->
                        local.update { loc ->
                            loc.copy(
                                scannedCount = imported,
                                totalCount = total,
                                currentScanningDir = "Organizing: $imported / $total"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeckViewModel", "Scan failed", e)
                local.update { it.copy(scanError = e.localizedMessage ?: e.toString()) }
            } finally {
                local.update {
                    if (it.scanError == null) {
                        it.copy(isScanning = false)
                    } else it
                }
            }
        }
    }

    fun dismissScanError() {
        local.update { it.copy(isScanning = false, scanError = null) }
    }

    private suspend fun scanDirectoryHelper(
        context: Context,
        treeUri: Uri,
        onUpdate: (count: Int, dirName: String, fileName: String?) -> Unit
    ): List<Uri> {
        val uris = mutableListOf<Uri>()
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = java.util.ArrayDeque<String>()
        queue.add(documentId)

        val rootName = treeUri.lastPathSegment ?: "Root"
        onUpdate(0, rootName, null)

        while (queue.isNotEmpty()) {
            val currentDirId = queue.removeFirst()
            val dirChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDirId)
            val shortDirName = currentDirId.substringAfterLast('/')
            onUpdate(uris.size, shortDirName, null)

            try {
                context.contentResolver.query(
                    dirChildrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIdx)
                        val mime = cursor.getString(mimeIdx)
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) else id.substringAfterLast('/')

                        // Skip macOS metadata and resource fork files starting with "._"
                        if (name.startsWith("._")) {
                            continue
                        }

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(id)
                        } else if (mime.startsWith("audio/") || isAudioExtension(name)) {
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            uris.add(fileUri)
                            onUpdate(uris.size, shortDirName, name)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DeckViewModel", "Error querying child documents in $currentDirId", e)
                throw e
            }
        }
        return uris
    }

    private fun isAudioExtension(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".ogg") || lower.endsWith(".aac")
    }



    // =================================================================================
    //  Track-end behavior  (C.5, driven by the PLAY setting)
    // =================================================================================

    private fun onTrackEnd(atEnd: Boolean) {
        haptics.play(HapticSpec.Boundary)
        if (!atEnd) return
        when (local.value.playMode) {
            PlayMode.REPEAT -> {
                engine.seekTo(0)
                engine.play()
            }
            PlayMode.RESUME -> {
                val tracks = library.tracks.value
                val cur = tracks.indexOfFirst { it.id == engine.state.value.trackId }
                if (cur in 0 until tracks.size - 1) {
                    engine.load(tracks[cur + 1])
                    engine.play()
                } else {
                    local.update { it.copy(transport = TransportState.STOPPED_AT_START) }
                }
            }
            PlayMode.STOP -> {
                engine.stop()
                local.update { it.copy(transport = TransportState.STOPPED_AT_START) }
            }
        }
    }

    // =================================================================================
    //  Overlay plumbing
    // =================================================================================

    /** Returns true if a transport press was consumed to dismiss an overlay instead. */
    private fun consumeOverlaysForTransport(): Boolean {
        val loc = local.value
        if (loc.varispeedOpen) { closeVarispeed(); return true }
        if (loc.menuOpen || loc.settingOpen) { closeMenu(); return true }
        if (loc.deleteOpen) { onDeleteConfirm(); return true }
        if (loc.fileInfoOpen || loc.gainOpen) { clearOverlays(); return true }
        return false
    }

    /** PLUS/MINUS routing inside open overlays; returns true if handled there. */
    private fun routeSkipToOverlay(dir: Int): Boolean {
        val loc = local.value
        return when {
            loc.settingOpen || loc.menuOpen -> {
                if (dir > 0) onMenuEnter() else onMenuBack()
                true
            }
            loc.deleteOpen -> { if (dir > 0) onDeleteConfirm() else onDeleteToggle(); true }
            loc.varispeedOpen -> { onSpeedAdjust(if (dir > 0) 4f else -4f); true }
            else -> false
        }
    }

    private fun clearOverlays() {
        parameterAccum = 0f
        local.update {
            it.copy(
                menuOpen = false, settingOpen = false, settingFieldIndex = 0,
                varispeedOpen = false, fileInfoOpen = false, deleteOpen = false,
                deleteSelectYes = false, gainOpen = false, volumeOverlay = false,
            )
        }
    }

    override fun onCleared() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        haptics.shutdown()
        engine.release()
    }

    companion object {
        private const val VARISPEED_MIN = -50
        private const val VARISPEED_MAX = 200
        private const val SPEED_UNITS_PER_PX = 0.18f   // drag → percent units
        private const val SCRUB_GAIN = 0.02f           // drag → reel speed
        private const val RESET_TURNS = 6              // reel turns to confirm RESET
        private const val MENU_STEP_DEG = 15f
        private const val SETTING_STEP_DEG = 15f
        private const val DELETE_STEP_DEG = 15f

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                DeckViewModel(container.appContext, container.audioEngine, container.hapticEngine, container.library)
            }
        }
    }
}
