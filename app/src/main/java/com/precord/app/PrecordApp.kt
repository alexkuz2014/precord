package com.precord.app

import android.app.Application
import android.content.Context
import com.precord.app.core.ClipRepository
import com.precord.app.core.SettingsStore

class PrecordApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var clips: ClipRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        clips = ClipRepository(this)
    }
}

val Context.precord: PrecordApp get() = applicationContext as PrecordApp
