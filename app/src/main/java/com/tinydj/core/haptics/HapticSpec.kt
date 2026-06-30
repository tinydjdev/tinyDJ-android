package com.tinydj.core.haptics

/**
 * Every tactile event in the app, expressed as intent rather than as a concrete
 * [android.os.VibrationEffect]. The [HapticEngine] maps these to primitives with
 * graceful fallback, and rate-limits the flood-prone ones.
 */
sealed interface HapticSpec {
    /** Transport / hardware button finger-down. */
    data object ButtonPress : HapticSpec
    /** Transport / hardware button finger-up. */
    data object ButtonRelease : HapticSpec

    data object Play : HapticSpec
    data object Pause : HapticSpec

    /** Finger landed on the reel. */
    data object ReelGrab : HapticSpec
    /** Finger lifted off the reel. */
    data object ReelRelease : HapticSpec

    /**
     * One rim detent crossed while *free-scrubbing* the reel. [speed] is |reel speed|
     * (≈ playback rate); the engine picks a softer/crisper tick and rate-limits these,
     * because a fast scratch can cross dozens of rim detents in a few milliseconds and
     * the finger feels a smear, not individual notches.
     *
     * This is explicitly NOT the primitive for adjusting a *value* — see [ValueTick].
     */
    data class Detent(val speed: Float) : HapticSpec

    /**
     * Exactly one crisp notch for crossing ONE discrete unit of an adjustable value:
     * varispeed (−99..+200), a system-menu setting, the volume readout, file selection,
     * the NAME editor. These are emitted 1:1 — the ViewModel fires one [ValueTick] per
     * integer the value crosses (six units moved ⇒ six ticks) — and the engine delivers
     * them without the free-scrub coalescing of [Detent] (only an extreme safety cap).
     * Identical feel for every kind of value adjust, so a dial always "counts" notches.
     */
    data object ValueTick : HapticSpec

    /** Fast spin: a single coalesced sensation instead of a hail of detents. */
    data class SpinBurst(val intensity: Float) : HapticSpec

    /** Reached the start or end of the track. */
    data object Boundary : HapticSpec

    /** Varispeed snapped back to exactly 1.0x. */
    data object SnapHome : HapticSpec

    /** A mode/toggle changed. */
    data object ModeToggle : HapticSpec

    /** Device vibrates while holding loop button A. */
    data object HoldVibrate : HapticSpec
}
