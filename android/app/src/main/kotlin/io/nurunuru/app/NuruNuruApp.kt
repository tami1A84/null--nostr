package io.nurunuru.app

import android.app.Application
import android.util.Log
import io.nurunuru.app.data.prefs.AppPreferences

private const val TAG = "NuruNuru-App"

class NuruNuruApp : Application() {

    lateinit var prefs: AppPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application started.")
        prefs = AppPreferences(this)
    }
}
