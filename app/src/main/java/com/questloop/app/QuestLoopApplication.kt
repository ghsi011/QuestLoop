package com.questloop.app

import android.app.Application
import com.questloop.app.di.AppContainer

class QuestLoopApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
