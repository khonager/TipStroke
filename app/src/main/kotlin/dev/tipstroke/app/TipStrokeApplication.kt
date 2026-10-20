package dev.tipstroke.app

import android.app.Application
import dev.tipstroke.drawing.android.LocalDiagnostics

class TipStrokeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalDiagnostics.initialize(this, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        LocalDiagnostics.record("memory trim level=$level")
    }
}
