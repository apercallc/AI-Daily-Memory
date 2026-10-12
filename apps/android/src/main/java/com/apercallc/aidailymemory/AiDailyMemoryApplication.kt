package com.apercallc.aidailymemory

import android.app.Application

class AiDailyMemoryApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SentryConfiguration.initialize(this)
        container = AppContainer(this)
        container.notifications.createChannels()
        // Every process start (launcher, notification action, boot, update) restores the
        // journal first; commands wait for it.
        container.restoreOnProcessStart()
    }
}
