package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(onClick = {
                            val service = MyAccessibilityService.instance
                            if (service != null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Switch to your target app in 3 seconds...",
                                    Toast.LENGTH_LONG
                                ).show()

                                handler.postDelayed({
                                    service.speakCurrentScreen()
                                }, 3000)
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Accessibility Service is not enabled or connected!",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }) {
                            Text(text = "Read this screen (3s delay)")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(onClick = {
                            MyAccessibilityService.instance?.stopSpeech()
                        }) {
                            Text(text = "Stop")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(onClick = {
                            val intent = Intent(Settings.ACTION_SETTINGS)
                            startActivity(intent)
                        }) {
                            Text(text = "Open Settings", modifier = Modifier) // or just Text("Open Settings")
                        }
                    }
                }
            }
        }
    }
}
