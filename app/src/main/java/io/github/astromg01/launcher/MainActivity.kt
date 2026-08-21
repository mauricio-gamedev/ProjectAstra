package io.github.astromg01.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.astromg01.launcher.ui.AstraApp
import io.github.astromg01.launcher.ui.AstraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AstraTheme {
                AstraApp()
            }
        }
    }
}
