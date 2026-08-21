package com.readyou.widget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Receives article taps from the widget and forwards them to the Read You app.
 * When integrated inside the Read You app directly, replace this with a direct
 * navigation call to the article detail screen.
 */
class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleId = intent.getStringExtra("articleId")
        if (articleId != null) {
            // Deep-link into Read You's article view
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("readyou://article/$articleId"))
                    .setPackage("io.github.ashinch.readyou")
            )
        }
        finish()
    }
}
