package com.raihan.coverdeck

import android.app.Application
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.privileged.Privileged

class CoverDeckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Listeners are registered process-wide so the overlay service can reach the
        // privileged link even when the activity was never opened.
        Privileged.init(this)
        RotationController.init(this)
    }
}
