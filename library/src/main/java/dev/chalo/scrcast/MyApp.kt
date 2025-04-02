package dev.chalo.scrcast// Change to your main app's package name

import android.app.Activity
import android.app.Application

class MyApp : Application() {
    companion object {
        lateinit var instance: MyApp
    }

    var currentActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}