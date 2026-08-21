package io.github.astromg01.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.astromg01.launcher.game.GameSurfaceActivity
import io.github.astromg01.launcher.ui.AstraApp
import io.github.astromg01.launcher.ui.AstraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AstraTheme {
                Box(Modifier.fillMaxSize()) {
                    AstraApp()
                    Button(
                        onClick = {
                            startActivity(Intent(this@MainActivity, GameSurfaceActivity::class.java))
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 94.dp)
                    ) {
                        Text("Bridge test")
                    }
                }
            }
        }
    }
}
