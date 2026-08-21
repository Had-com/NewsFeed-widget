package com.readyou.widget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Receives article taps from the Glance widget.
 * Tries to open the article URL inside the Read You app;
 * falls back to the system browser if Read You is not installed or
 * does not handle web URLs.
 */
class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("articleUrl")
        if (!url.isNullOrBlank()) {
            val uri = Uri.parse(url)
            // Prefer Read You's internal browser when available
            val readYouIntent = Intent(Intent.ACTION_VIEW, uri)
                .setPackage("io.github.ashinch.readyou")
            val opened = runCatching {
                if (readYouIntent.resolveActivity(packageManager) != null) {
                    startActivity(readYouIntent)
                    true
                } else false
            }.getOrDefault(false)

            if (!opened) {
                // Fall back to default browser
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
        }
        finish()
    }
}
