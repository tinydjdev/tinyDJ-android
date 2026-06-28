package com.tinydj.core.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pure-Kotlin engine for @Preview and emulator UI development (no NDK, no audio).
 * Advances a simulated playhead so the reel spins.
 */
class FakeAudioEngine(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AudioEngine {

    private val rate = 48000
    private val _state = MutableStateFlow(
        EngineState(fileSampleRate = rate, totalFrames = rate.toLong() * 210),
    )
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(16)
                val s = _state.value
                if ((s.isPlaying || s.isScrubbing) && s.totalFrames > 1) {
                    val advance = (rate * 0.016 * s.speed).toLong()
                    val next = (s.positionFrames + advance).coerceIn(0, s.totalFrames - 1)
                    
                    // Simulate dynamic fluctuating levels (0.1 to 0.9) when active
                    val speedFactor = if (s.isScrubbing) (Math.abs(s.speed) * 0.5f).coerceAtMost(1f) else 1f
                    val baseL = 0.2f + kotlin.math.sin(System.currentTimeMillis() / 200.0).toFloat() * 0.1f
                    val baseR = 0.2f + kotlin.math.cos(System.currentTimeMillis() / 250.0).toFloat() * 0.12f
                    val noiseL = Math.random().toFloat() * 0.4f
                    val noiseR = Math.random().toFloat() * 0.4f
                    
                    val vuL = ((baseL + noiseL) * speedFactor).coerceIn(0f, 0.95f)
                    val vuR = ((baseR + noiseR) * speedFactor).coerceIn(0f, 0.95f)
                    
                    _state.update { it.copy(positionFrames = next, vuLeft = vuL, vuRight = vuR) }
                } else {
                    _state.update { it.copy(vuLeft = 0f, vuRight = 0f) }
                }
            }
        }
    }

    override fun load(track: AudioTrack) = _state.update {
        EngineState(trackId = track.id, fileSampleRate = rate, totalFrames = rate.toLong() * (track.durationMs / 1000))
    }
    override fun play() = _state.update { it.copy(isPlaying = true, speed = 1f) }
    override fun pause() = _state.update { it.copy(isPlaying = false) }
    override fun togglePlay() { if (_state.value.isPlaying) pause() else play() }
    override fun seekTo(positionMs: Long) = _state.update { it.copy(positionFrames = positionMs * rate / 1000) }
    override fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }
    override fun setVolume(volume: Float) = _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    override fun setLoop(enabled: Boolean) = _state.update { it.copy(loop = enabled) }
    override fun stop() = _state.update { it.copy(isPlaying = false, positionFrames = 0) }
    override fun beginScrub() = _state.update { it.copy(isScrubbing = true, speed = 0f) }
    override fun setScrubSpeed(speed: Float) = _state.update { it.copy(speed = speed) }
    override fun scrubByFrames(deltaFrames: Double) = _state.update {
        it.copy(positionFrames = (it.positionFrames + deltaFrames.toLong()).coerceIn(0, it.totalFrames - 1))
    }
    override fun endScrub(wasPlaying: Boolean) = _state.update { it.copy(isScrubbing = false, isPlaying = wasPlaying, speed = if (wasPlaying) 1f else 0f) }
    override fun capabilities() = EngineCapabilities(true, true, true)
    override fun release() {}
    override fun updateTrackId(oldId: String, newId: String) {
        if (_state.value.trackId == oldId) {
            _state.update { it.copy(trackId = newId) }
        }
    }
    override fun startRecording() {}
    override fun stopRecording() {}
    override fun pullRecording(buffer: ShortArray): Int {
        for (i in buffer.indices) {
            buffer[i] = 0
        }
        return buffer.size
    }
    override fun getDeviceRate(): Int = 48000
}
