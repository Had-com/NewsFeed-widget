package com.readyou.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Read You Widget", fontSize = 22.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "To add the widget to your home screen:\n\n" +
                        "1. Go to the home screen\n" +
                        "2. Long-press an empty area\n" +
                        "3. Tap Widgets\n" +
                        "4. Find Read You Feeds\n" +
                        "5. Drag it onto your home screen",
                        fontSize = 15.sp,
                        textAlign = TextAlign.Start,
                        lineHeight = 24.sp,
                    )
                }
            }
        }
    }
}
