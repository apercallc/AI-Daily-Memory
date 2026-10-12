package com.apercallc.aidailymemory.wear

import android.app.Application

class WearDailyMemoryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SentryConfiguration.initialize(this)
    }
}
