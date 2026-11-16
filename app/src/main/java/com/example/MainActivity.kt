package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.core.security.configureAppCheck
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.MyraaSuperBrainTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        // STEP 2: Enable edge-to-edge rendering and mount auth-gated Compose root
        enableEdgeToEdge()
        setContent {
            MyraaSuperBrainTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
    }
}
