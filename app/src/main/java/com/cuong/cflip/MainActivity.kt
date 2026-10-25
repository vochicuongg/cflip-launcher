package com.cuong.cflip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cuong.cflip.ui.MainScreen
import com.cuong.cflip.ui.theme.CFlipTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CFlipTheme { MainScreen() }
        }
    }
}
