package com.example.hello_xr

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.hello_xr.ui.HelloAndroidXRApp
import com.example.hello_xr.ui.theme.HelloxrTheme

class MainActivity : ComponentActivity() {

  @SuppressLint("RestrictedApi")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent { HelloxrTheme { HelloAndroidXRApp() } }
  }
}
