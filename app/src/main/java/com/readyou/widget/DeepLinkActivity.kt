package com.readyou.widget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.readyou.widget.data.ReadStatusStore
import com.readyou.widget.glance.WidgetWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DeepLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleId  = intent.getStringExtra("articleId")  ?: ""
        val articleUrl = intent.getStringExtra("articleUrl") ?: ""

        openUrl(articleUrl)

        // Call finish() synchronously — Theme.NoDisplay crashes on Android 10+ if the
        // activity survives past onResume(). Use applicationContext so the coroutine
        // scope outlives the activity.
        val appContext = applicationContext
        finish()

        if (articleId.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                ReadStatusStore(appContext).markRead(articleId)
                WidgetWorker.refreshNow(appContext)
            }
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        val uri = Uri.parse(url)
        // Try both known Read You package names (me.ash.reader is the main one)
        for (pkg in listOf("me.ash.reader", "io.github.ashinch.readyou")) {
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
            val opened = runCatching {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent); true
                } else false
            }.getOrDefault(false)
            if (opened) return
        }
        // Fall back to system browser
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
