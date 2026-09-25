package com.apercallc.aidailymemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.apercallc.aidailymemory.service.RecordingService
import com.apercallc.aidailymemory.ui.AiDailyMemoryApp
import com.apercallc.aidailymemory.ui.CaptureViewModel
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as AiDailyMemoryApplication).container

    private val viewModel: CaptureViewModel by viewModels {
        // Commands go to the service while this activity is visible, which is what lets the
        // service start in the foreground with the microphone (ADR-0010).
        CaptureViewModel.Factory(container) { action ->
            RecordingService.send(applicationContext, action)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AiDailyMemoryTheme {
                AiDailyMemoryApp(viewModel = viewModel, permissions = container.permissions)
            }
        }
    }
}
