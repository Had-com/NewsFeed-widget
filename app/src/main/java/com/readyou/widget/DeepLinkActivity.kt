package com.readyou.widget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.readyou.widget.data.ReadStatusStore
import com.readyou.widget.glance.WidgetWorker
import kotlinx.coroutines.launch

class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleId  = intent.getStringExtra("articleId")  ?: ""
        val articleUrl = intent.getStringExtra("articleUrl") ?: ""

        // Open the article URL immediately — don't wait for the DataStore write
        openUrl(articleUrl)

        // Mark as read then refresh the widget, then close this transparent activity
        lifecycleScope.launch {
            if (articleId.isNotBlank()) {
                ReadStatusStore(this@DeepLinkActivity).markRead(articleId)
                WidgetWorker.refreshNow(this@DeepLinkActivity)
            }
            finish()
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        val uri = Uri.parse(url)
        val readYouIntent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("io.github.ashinch.readyou")
        val opened = runCatching {
            if (readYouIntent.resolveActivity(packageManager) != null) {
                startActivity(readYouIntent); true
            } else false
        }.getOrDefault(false)
        if (!opened) runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
