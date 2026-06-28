package com.tinydj.core.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Production [AudioEngine]: a thin Kotlin shell over the native Oboe + dr_libs
 * engine in `native_audio_engine.cpp`. Owns a position-polling loop that mirrors
 * the native read pointer into [state] for the UI.
 */
class OboeAudioEngine(
    private val appContext: Context,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AudioEngine {

    private val handle: Long = nativeCreate()
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var baseSpeed: Float = 1f

    init {
        if (handle != 0L) nativeStart(handle)
    }

    override fun load(track: AudioTrack) {
        ioScope.launch {
            nativePause(handle)
            val fd = openFd(track.uriString) ?: run {
                Log.e(TAG, "load: could not open ${track.uriString}")
                return@launch
            }
            // Native takes ownership of the fd and closes it.
            val total = nativeLoadFd(handle, fd, track.isFlac)
            if (total < 0) {
                Log.e(TAG, "load: decode failed for ${track.title}")
                return@launch
            }
            baseSpeed = 1f
            _state.update {
                EngineState(
                    trackId = track.id,
                    isPlaying = false,
                    positionFrames = 0,
                    totalFrames = nativeTotalFrames(handle),
                    fileSampleRate = nativeFileRate(handle),
                    speed = 1f,
                )
            }
            startPolling()
        }
    }

    override fun play() {
        nativeSetSpeed(handle, baseSpeed.toDouble())
        nativePlay(handle)
        _state.update { it.copy(isPlaying = true, speed = baseSpeed) }
    }

    override fun pause() {
        nativePause(handle)
        _state.update { it.copy(isPlaying = false) }
    }

    override fun togglePlay() {
        if (_state.value.isPlaying) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        val rate = _state.value.fileSampleRate
        if (rate <= 0) return
        nativeSeekFrame(handle, positionMs * rate / 1000)
    }

    override fun setSpeed(speed: Float) {
        baseSpeed = speed
        if (_state.value.isPlaying && !_state.value.isScrubbing) {
            nativeSetSpeed(handle, speed.toDouble())
        }
        _state.update { it.copy(speed = speed) }
    }

    override fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        nativeSetVolume(handle, v)
        _state.update { it.copy(volume = v) }
    }

    override fun setLoop(enabled: Boolean) {
        nativeSetLoop(handle, enabled)
        _state.update { it.copy(loop = enabled) }
    }

    override fun stop() {
        nativePause(handle)
        nativeSeekFrame(handle, 0)
        _state.update { it.copy(isPlaying = false, positionFrames = 0) }
    }

    override fun beginScrub() {
        nativeSetScrubbing(handle, true)
        nativeSetSpeed(handle, 0.0) // held still until the finger moves
        _state.update { it.copy(isScrubbing = true) }
    }

    override fun setScrubSpeed(speed: Float) {
        nativeSetSpeed(handle, speed.toDouble())
        _state.update { it.copy(speed = speed) }
    }

    override fun scrubByFrames(deltaFrames: Double) {
        nativeScrubBy(handle, deltaFrames)
    }

    override fun endScrub(wasPlaying: Boolean) {
        nativeSetScrubbing(handle, false)
        if (wasPlaying) {
            nativeSetSpeed(handle, baseSpeed.toDouble())
            nativePlay(handle)
        } else {
            nativeSetSpeed(handle, 0.0)
            nativePause(handle)
        }
        _state.update { it.copy(isScrubbing = false, isPlaying = wasPlaying, speed = if (wasPlaying) baseSpeed else 0f) }
    }

    override fun capabilities() = EngineCapabilities(
        sampleAccurateScrub = true,
        audibleScratch = true,
        reversePlayback = true,
    )

    override fun release() {
        pollJob?.cancel()
        ioScope.cancel()
        if (handle != 0L) nativeRelease(handle)
    }

    override fun updateTrackId(oldId: String, newId: String) {
        if (_state.value.trackId == oldId) {
            _state.update { it.copy(trackId = newId) }
        }
    }

    override fun startRecording() {
        if (handle != 0L) nativeStartRecording(handle)
    }

    override fun stopRecording() {
        if (handle != 0L) nativeStopRecording(handle)
    }

    override fun pullRecording(buffer: ShortArray): Int {
        return if (handle != 0L) nativePullRecording(handle, buffer) else 0
    }

    override fun getDeviceRate(): Int {
        return if (handle != 0L) nativeDeviceRate(handle) else 48000
    }

    private external fun nativeStartRecording(handle: Long)
    private external fun nativeStopRecording(handle: Long)
    private external fun nativePullRecording(handle: Long, buffer: ShortArray): Int
    private external fun nativeDeviceRate(handle: Long): Int

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = ioScope.launch {
            while (true) {
                val pos = nativePositionFrames(handle)
                val vuL = nativeVuLeft(handle)
                val vuR = nativeVuRight(handle)
                // Native flips isPlaying to false when it hits a boundary; mirror that.
                _state.update { it.copy(positionFrames = pos, vuLeft = vuL, vuRight = vuR) }
                delay(POLL_MS)
            }
        }
    }

    private fun openFd(uriString: String): Int? = try {
        val uri = android.net.Uri.parse(uriString)
        val pfd = if (uri.scheme == "file") {
            ParcelFileDescriptor.open(File(uri.path ?: ""), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            appContext.contentResolver.openFileDescriptor(uri, "r")
        }
        pfd?.detachFd() // ownership transfers to native, which closes it
    } catch (t: Throwable) {
        Log.e(TAG, "openFd failed for $uriString", t)
        null
    }

    // --- JNI ---
    private external fun nativeCreate(): Long
    private external fun nativeLoadFd(handle: Long, fd: Int, isFlac: Boolean): Long
    private external fun nativeStart(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeSetSpeed(handle: Long, speed: Double)
    private external fun nativeSetScrubbing(handle: Long, scrubbing: Boolean)
    private external fun nativeSetVolume(handle: Long, volume: Float)
    private external fun nativeSetLoop(handle: Long, on: Boolean)
    private external fun nativeSeekFrame(handle: Long, frame: Long)
    private external fun nativeScrubBy(handle: Long, deltaFrames: Double)
    private external fun nativePositionFrames(handle: Long): Long
    private external fun nativeTotalFrames(handle: Long): Long
    private external fun nativeFileRate(handle: Long): Int
    private external fun nativeVuLeft(handle: Long): Float
    private external fun nativeVuRight(handle: Long): Float
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val TAG = "OboeAudioEngine"
        private const val POLL_MS = 16L // ~60 Hz, feeds the reel angle

        init {
            System.loadLibrary("tinydj-audio")
        }
    }
}
