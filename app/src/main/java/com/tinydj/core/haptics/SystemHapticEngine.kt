package com.tinydj.core.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/**
 * [HapticEngine] backed by [VibratorManager] (min SDK 31, so primitives, SPIN and
 * reliable support checks are all available). Builds rich primitive compositions
 * when the device supports them and walks a fallback ladder otherwise:
 *   primitive composition -> predefined effect -> one-shot -> no-op.
 */
class SystemHapticEngine(context: Context) : HapticEngine {

    private val vibrator: Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator

    private val throttler = HapticThrottler()

    // VibrationAttributes + the (effect, attributes) vibrate() overload are API 33.
    // Built only when available; below 33 we use the single-arg vibrate().
    private val touchAttrs: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
        } else {
            null
        }

    override val capabilities: HapticCapabilities = detectCapabilities()

    @Volatile override var intensity: Float = 1f

    override fun play(spec: HapticSpec) {
        if (!capabilities.hasVibrator) return
        when (spec) {
            HapticSpec.ButtonPress -> submit { vibrate(prim(Composition.PRIMITIVE_CLICK, 1.0f, VibrationEffect.EFFECT_CLICK, 12)) }
            HapticSpec.ButtonRelease -> submit { vibrate(prim(Composition.PRIMITIVE_TICK, 0.5f, VibrationEffect.EFFECT_TICK, 6)) }
            HapticSpec.Play -> submit { vibrate(combo(Composition.PRIMITIVE_QUICK_RISE to 0.5f, Composition.PRIMITIVE_CLICK to 1.0f, fallback = VibrationEffect.EFFECT_HEAVY_CLICK)) }
            HapticSpec.Pause -> submit { vibrate(combo(Composition.PRIMITIVE_QUICK_FALL to 0.5f, Composition.PRIMITIVE_TICK to 0.6f, fallback = VibrationEffect.EFFECT_CLICK)) }
            HapticSpec.ReelGrab -> submit { vibrate(prim(Composition.PRIMITIVE_THUD, 0.7f, VibrationEffect.EFFECT_HEAVY_CLICK, 18)) }
            HapticSpec.ReelRelease -> submit { vibrate(prim(Composition.PRIMITIVE_TICK, 0.4f, VibrationEffect.EFFECT_TICK, 6)) }
            HapticSpec.Boundary -> submit { vibrate(predefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) }
            HapticSpec.SnapHome -> submit { vibrate(prim(Composition.PRIMITIVE_TICK, 0.8f, VibrationEffect.EFFECT_TICK, 8)) }
            HapticSpec.ModeToggle -> submit { vibrate(combo(Composition.PRIMITIVE_CLICK to 0.7f, Composition.PRIMITIVE_QUICK_RISE to 0.4f, fallback = VibrationEffect.EFFECT_CLICK)) }
            HapticSpec.HoldVibrate -> submit {
                val effect = if (capabilities.hasAmplitudeControl) {
                    VibrationEffect.createOneShot(5000, (255 * intensity).toInt().coerceIn(1, 255))
                } else {
                    VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrate(effect)
            }

            is HapticSpec.Detent -> {
                // Slow, deliberate scrub -> crisp TICK; very fine -> LOW_TICK.
                val primitive = if (spec.speed < 0.4f) Composition.PRIMITIVE_LOW_TICK else Composition.PRIMITIVE_TICK
                val scale = (0.35f + 0.5f * spec.speed.coerceIn(0f, 1f))
                throttler.submitDetent { vibrate(prim(primitive, scale, VibrationEffect.EFFECT_TICK, 6)) }
            }

            HapticSpec.ValueTick -> {
                // One crisp notch per discrete value unit. Delivered 1:1 (not coalesced
                // like the free-scrub Detent above) so every unit a dial crosses is felt.
                throttler.submitValueTick { vibrate(prim(Composition.PRIMITIVE_TICK, 0.7f, VibrationEffect.EFFECT_TICK, 7)) }
            }

            is HapticSpec.SpinBurst -> submit {
                vibrate(prim(Composition.PRIMITIVE_SPIN, spec.intensity.coerceIn(0.2f, 1f), VibrationEffect.EFFECT_HEAVY_CLICK, 20))
            }
        }
    }

    override fun cancel() {
        vibrator.cancel()
    }

    override fun shutdown() {
        throttler.shutdown()
    }

    // --- effect construction with fallback ---

    private fun submit(block: () -> Unit) = throttler.submit(block)

    private fun vibrate(effect: VibrationEffect) {
        val attrs = touchAttrs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && attrs != null) {
            vibrator.vibrate(effect, attrs)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect)
        }
    }

    /** One primitive, scaled by user intensity, falling back to a predefined/one-shot. */
    private fun prim(primitive: Int, scale: Float, predefined: Int, oneShotMs: Long): VibrationEffect {
        if (primitive in capabilities.supportedPrimitives) {
            return VibrationEffect.startComposition()
                .addPrimitive(primitive, (scale * intensity).coerceIn(0.05f, 1f))
                .compose()
        }
        return predefinedOrOneShot(predefined, oneShotMs)
    }

    /** A two-primitive composition, or a single predefined fallback. */
    private fun combo(vararg steps: Pair<Int, Float>, fallback: Int): VibrationEffect {
        if (steps.all { it.first in capabilities.supportedPrimitives }) {
            val c = VibrationEffect.startComposition()
            steps.forEach { c.addPrimitive(it.first, (it.second * intensity).coerceIn(0.05f, 1f)) }
            return c.compose()
        }
        return predefined(fallback)
    }

    private fun predefined(effect: Int): VibrationEffect = VibrationEffect.createPredefined(effect)

    private fun predefinedOrOneShot(predefined: Int, oneShotMs: Long): VibrationEffect =
        runCatching { VibrationEffect.createPredefined(predefined) }
            .getOrElse { VibrationEffect.createOneShot(oneShotMs, VibrationEffect.DEFAULT_AMPLITUDE) }

    private fun detectCapabilities(): HapticCapabilities {
        if (!vibrator.hasVibrator()) {
            return HapticCapabilities(false, false, emptySet())
        }
        val supported = ALL_PRIMITIVES.filter { vibrator.areAllPrimitivesSupported(it) }.toSet()
        return HapticCapabilities(
            hasVibrator = true,
            hasAmplitudeControl = vibrator.hasAmplitudeControl(),
            supportedPrimitives = supported,
        )
    }

    private companion object {
        val ALL_PRIMITIVES = intArrayOf(
            Composition.PRIMITIVE_CLICK,
            Composition.PRIMITIVE_TICK,
            Composition.PRIMITIVE_LOW_TICK,
            Composition.PRIMITIVE_QUICK_RISE,
            Composition.PRIMITIVE_QUICK_FALL,
            Composition.PRIMITIVE_SLOW_RISE,
            Composition.PRIMITIVE_THUD,
            Composition.PRIMITIVE_SPIN,
        ).toList()
    }
}
