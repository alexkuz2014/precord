package com.precord.app

import android.app.Application
import android.content.Context
import com.precord.app.core.ClipRepository
import com.precord.app.core.Fmt
import com.precord.app.core.SettingsStore

class PrecordApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var clips: ClipRepository
        private set

    override fun onCreate() {
        super.onCreate()
        Fmt.init(resources)
        settings = SettingsStore(this)
        clips = ClipRepository(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Fmt.init(resources)
    }
}

val Context.precord: PrecordApp get() = applicationContext as PrecordApp
