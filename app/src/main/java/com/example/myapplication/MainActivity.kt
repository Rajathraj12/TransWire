package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.presentation.navigation.NavGraph
import com.example.myapplication.presentation.settings.AppThemeMode
import com.example.myapplication.presentation.settings.ServerPreferences
import com.example.myapplication.presentation.settings.ThemePreferences
import com.example.myapplication.presentation.theme.TranswireTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalingClient.updateServerUrl(ServerPreferences.getServerUrl(this))
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf(ThemePreferences.getThemeMode(this)) }
            val systemDark = isSystemInDarkTheme()
            val useDarkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> systemDark
                AppThemeMode.LIGHT  -> false
                AppThemeMode.DARK   -> true
            }

            TranswireTheme(darkTheme = useDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(
                        navController     = navController,
                        currentThemeMode  = themeMode,
                        onThemeModeChange = { newMode ->
                            themeMode = newMode
                            ThemePreferences.setThemeMode(this, newMode)
                        }
                    )
                }
            }
        }
    }
}
