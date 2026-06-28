package com.tinydj

import android.app.Application
import com.tinydj.di.AppContainer

class TinyDjApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
