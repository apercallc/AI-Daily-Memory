package com.apercallc.aidailymemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.apercallc.aidailymemory.service.RecordingService
import com.apercallc.aidailymemory.ui.AppRoot
import com.apercallc.aidailymemory.ui.CaptureViewModel
import com.apercallc.aidailymemory.ui.sessions.SessionsViewModel
import com.apercallc.aidailymemory.ui.memory.MemoryViewModel
import com.apercallc.aidailymemory.ui.settings.SettingsViewModel
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import com.apercallc.aidailymemory.update.UpdateViewModel

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

    private val memoryViewModel: MemoryViewModel by viewModels {
        MemoryViewModelFactory(container)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(container)
    }

    private val updateViewModel: UpdateViewModel by viewModels {
        UpdateViewModel.Factory(container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle()
            val onboardingDone by container.settings.onboardingDone.collectAsStateWithLifecycle()
            val dark = themeMode.isDark(isSystemInDarkTheme())
            // The bar icons must contrast with the chosen theme, which may differ from the phone's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            AiDailyMemoryTheme(darkTheme = dark) {
                AppRoot(
                    capture = viewModel,
                    sessions = { sessionsViewModel },
                    memory = { memoryViewModel },
                    settings = { settingsViewModel },
                    updates = { updateViewModel },
                    permissions = container.permissions,
                    onboardingDone = onboardingDone,
                    onOnboardingFinished = { container.settings.setOnboardingDone(true) },
                )
            }
        }
    }
}

class MemoryViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MemoryViewModel(container) as T
}