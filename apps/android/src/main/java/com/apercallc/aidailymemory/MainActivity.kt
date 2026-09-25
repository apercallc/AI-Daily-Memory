package com.apercallc.aidailymemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.apercallc.aidailymemory.service.RecordingService
import com.apercallc.aidailymemory.ui.AppRoot
import com.apercallc.aidailymemory.ui.CaptureViewModel
import com.apercallc.aidailymemory.ui.sessions.SessionsViewModel
import com.apercallc.aidailymemory.ui.settings.SettingsViewModel
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

    private val sessionsViewModel: SessionsViewModel by viewModels {
        SessionsViewModel.Factory(container)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AiDailyMemoryTheme {
                AppRoot(
                    capture = viewModel,
                    sessions = { sessionsViewModel },
                    settings = { settingsViewModel },
                    permissions = container.permissions,
                )
            }
        }
    }
}
