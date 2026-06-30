package com.tinydj.core.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * A loadable, scrubbable audio track. [uriString] is a persisted SAF/content URI.
 * [isFlac] picks the native decoder; everything else is display metadata.
 */
data class AudioTrack(
    val id: String,
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String = "unknown",
    val date: String = "unknown",
    val lastModified: Long = 0L,
    val durationMs: Long,
    val isFlac: Boolean,
    val trackNumber: Int = 0,
)

/** What the current engine implementation can actually do, for UI affordances. */
data class EngineCapabilities(
    val sampleAccurateScrub: Boolean,
    val audibleScratch: Boolean,
    val reversePlayback: Boolean,
)

/** Immutable snapshot of the engine, collected by the ViewModel/UI. */
data class EngineState(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val isScrubbing: Boolean = false,
    val positionFrames: Long = 0,
    val totalFrames: Long = 0,
    val fileSampleRate: Int = 0,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val loop: Boolean = false,
    val vuLeft: Float = 0f,
    val vuRight: Float = 0f,
) {
    val positionMs: Long
        get() = if (fileSampleRate > 0) positionFrames * 1000 / fileSampleRate else 0
    val durationMs: Long
        get() = if (fileSampleRate > 0) totalFrames * 1000 / fileSampleRate else 0
    val progress: Float
        get() = if (totalFrames > 1) (positionFrames.toFloat() / (totalFrames - 1)).coerceIn(0f, 1f) else 0f
}

/**
 * The seam between the UI/ViewModel and the audio backend. The reel "grabs" the
 * audio through [beginScrub] / [setScrubSpeed] / [endScrub]; the implementation
 * decides how faithfully that becomes sound.
 */
interface AudioEngine {
    val state: StateFlow<EngineState>

    fun load(track: AudioTrack)
    fun play()
    fun pause()
    fun togglePlay()

    /** Coarse transport seek (e.g. tapping the progress strip). */
    fun seekTo(positionMs: Long)

    /** Sustained varispeed while playing (1.0 = normal; pitch tracks speed). */
    fun setSpeed(speed: Float)

    /** Master output gain (0.0 = silent, 1.0 = unity). */
    fun setVolume(volume: Float)

    /** When enabled, playback wraps at the boundary instead of stopping. */
    fun setLoop(enabled: Boolean)

    /** Set start and end frames for loop range. Set both to -1 to disable. */
    fun setLoopPoints(startFrame: Long, endFrame: Long)

    /** Stop transport: pause and rewind to the start. */
    fun stop()

    /** Finger went down on the reel. */
    fun beginScrub()

    /**
     * Instantaneous reel speed implied by finger angular velocity. May be negative
     * (reverse). This is what produces the scratch sound.
     */
    fun setScrubSpeed(speed: Float)

    /** Optional fine positional nudge (1:1 jog), independent of [setScrubSpeed]. */
    fun scrubByFrames(deltaFrames: Double)

    /** Finger lifted. [wasPlaying] restores transport intent. */
    fun endScrub(wasPlaying: Boolean)

    fun capabilities(): EngineCapabilities
    fun release()

    /** Update track ID if it matches oldId (used when a track file is renamed). */
    fun updateTrackId(oldId: String, newId: String)

    fun startRecording()
    fun stopRecording()
    fun pullRecording(buffer: ShortArray): Int
    fun getDeviceRate(): Int
    fun setTapeFlutter(enabled: Boolean)
    fun setSlipMode(enabled: Boolean)
}
