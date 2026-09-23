package com.apercallc.aidailymemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.apercallc.aidailymemory.ui.AiDailyMemoryApp
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AiDailyMemoryTheme {
                AiDailyMemoryApp()
            }
        }
    }
}
