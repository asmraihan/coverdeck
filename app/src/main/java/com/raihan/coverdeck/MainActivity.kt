package com.raihan.coverdeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raihan.coverdeck.privileged.Privileged
import com.raihan.coverdeck.ui.DeckApp
import com.raihan.coverdeck.ui.DeckViewModel
import com.raihan.coverdeck.ui.theme.CoverDeckTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoverDeckTheme {
                val model: DeckViewModel = viewModel()
                DeckApp(model)

                // The privileged link comes up asynchronously; run the startup recovery
                // pass the first time it is actually ready.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            Privileged.status.collectLatest { status ->
                                if (status is Privileged.Status.Ready) model.onPrivilegedReady()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Privileged.refresh()
    }
}
