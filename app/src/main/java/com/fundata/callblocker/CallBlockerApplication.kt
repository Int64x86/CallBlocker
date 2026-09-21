package com.fundata.callblocker

import android.app.Application
import com.fundata.callblocker.data.DiagnosticsStore

class CallBlockerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticsStore.initialize(this)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DiagnosticsStore.saveError(
                "Uncaught exception, thread ${thread.name}",
                error,
                immediate = true
            )
            previousHandler?.uncaughtException(thread, error)
        }
    }
}
