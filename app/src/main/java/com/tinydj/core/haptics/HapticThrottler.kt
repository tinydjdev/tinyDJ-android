package com.tinydj.core.haptics

import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Keeps the vibrator off the UI/drag thread and prevents flooding. Detent events
 * that arrive faster than [DETENT_MIN_INTERVAL_MS] are dropped (the [com.tinydj.ui.deck.ReelDisc]
 * escalates to a single SpinBurst at high speed, so dropping detents there is correct).
 */
class HapticThrottler {
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "haptics").apply { priority = Thread.MAX_PRIORITY }
    }

    @Volatile private var lastDetentAt = 0L
    @Volatile private var lastValueTickAt = 0L

    fun submit(block: () -> Unit) {
        if (!exec.isShutdown) exec.execute(block)
    }

    /**
     * Free-scrub rim detent. Returns true if accepted (not throttled). A rapid scratch
     * crosses far more notches than the vibrator can resolve, so detents arriving faster
     * than [DETENT_MIN_INTERVAL_MS] are intentionally dropped (the reel escalates to a
     * single SpinBurst at high speed).
     */
    fun submitDetent(block: () -> Unit): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastDetentAt < DETENT_MIN_INTERVAL_MS) return false
        lastDetentAt = now
        submit(block)
        return true
    }

    /**
     * Per-unit *value* detent. Delivered 1:1 — NOT coalesced like [submitDetent] — so a
     * six-unit value change buzzes six times. Only an extreme safety cap applies
     * ([VALUE_TICK_MIN_INTERVAL_MS], ~60 Hz) to protect the vibrator from a pathological
     * flood; normal value scrolling stays well under it. Returns true if accepted.
     */
    fun submitValueTick(block: () -> Unit): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastValueTickAt < VALUE_TICK_MIN_INTERVAL_MS) return false
        lastValueTickAt = now
        submit(block)
        return true
    }

    fun shutdown() = exec.shutdownNow()

    companion object {
        const val DETENT_MIN_INTERVAL_MS = 18L // ~55 Hz ceiling on free-scrub detents
        const val VALUE_TICK_MIN_INTERVAL_MS = 16L // ~60 Hz hard safety cap only
    }
}
