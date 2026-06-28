package com.tinydj.core.haptics

/** Detected capabilities of the device's vibrator, used to pick effects + fallbacks. */
data class HapticCapabilities(
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val supportedPrimitives: Set<Int>,
)

interface HapticEngine {
    val capabilities: HapticCapabilities

    /** User-facing intensity scale, 0f..1f. Persisted in settings. */
    var intensity: Float

    fun play(spec: HapticSpec)

    /** Stop any in-flight vibration (e.g. on reel release). */
    fun cancel()

    fun shutdown()
}
