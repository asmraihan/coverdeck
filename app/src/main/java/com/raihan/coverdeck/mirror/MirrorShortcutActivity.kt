package com.raihan.coverdeck.mirror

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.raihan.coverdeck.MainActivity
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "Mirror" launcher entry: one tap from the cover launcher into mirror mode. Waits a
 * moment for the Shizuku link on a cold start, then gets out of the way; if the link
 * never comes up it opens CoverDeck instead, where Setup explains why.
 */
class MirrorShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val ready = withTimeoutOrNull(4_000) {
                Privileged.status.first { it is Privileged.Status.Ready }
            } != null
            // start() is false when the accessibility host is off; CoverDeck's Mirror
            // page explains how to switch it on.
            if (!ready || !MirrorSession.start()) {
                startActivity(Intent(this@MirrorShortcutActivity, MainActivity::class.java))
            }
            finish()
        }
    }
}
