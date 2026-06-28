package com.tinydj.di

import android.content.Context
import com.tinydj.core.audio.AudioEngine
import com.tinydj.core.audio.OboeAudioEngine
import com.tinydj.core.haptics.HapticEngine
import com.tinydj.core.haptics.SystemHapticEngine
import com.tinydj.data.library.LibraryRepository

/**
 * Manual dependency container, built once in [com.tinydj.TinyDjApp]. Small app =
 * no need for Hilt. Holds process-lifetime singletons.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val audioEngine: AudioEngine by lazy { OboeAudioEngine(appContext) }
    val hapticEngine: HapticEngine by lazy { SystemHapticEngine(appContext) }
    val library: LibraryRepository by lazy { LibraryRepository(appContext) }
}
