package com.raihan.coverdeck

import android.app.Application
import com.raihan.coverdeck.feature.AutoRotate
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.mirror.MirrorSession
import com.raihan.coverdeck.nav.HomeLongPress
import com.raihan.coverdeck.privileged.Privileged

class CoverDeckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Listeners are registered process-wide so the overlay service can reach the
        // privileged link even when the activity was never opened.
        Privileged.init(this)
        RotationController.init(this)
        AutoRotate.init(this)
        HomeLongPress.init(this)
        MirrorSession.init(this)
    }
}
