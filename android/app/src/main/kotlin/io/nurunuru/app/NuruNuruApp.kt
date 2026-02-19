package io.nurunuru.app

import android.app.Application
import io.nurunuru.app.data.prefs.AppPreferences

class NuruNuruApp : Application() {

    lateinit var prefs: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
    }
}
