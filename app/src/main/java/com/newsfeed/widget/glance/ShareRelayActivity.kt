package com.newsfeed.widget.glance

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

/**
 * A share button tapped from the widget can't call Intent.createChooser() + startActivity()
 * directly and reliably — Glance's actionStartActivity() only reliably launches a single,
 * unambiguous target from a widget's PendingIntent context (confirmed: plain ACTION_VIEW
 * works, but ACTION_SEND with a chooser or multiple share targets does not, even once each
 * row's intent is made distinct to avoid Glance's action-conflation). This tiny relay
 * activity is itself a single, unambiguous target Glance can launch cleanly; once it's
 * actually running as a real foreground Activity, it has full standing to build and launch the
 * chooser normally, the same way any ordinary "Share" button in a full app would.
 *
 * Two independent modes, chosen by which Intent extra is present:
 * - EXTRA_ARTICLE_URL: share that URL immediately, no UI ever shown (the original,
 *   per-article mode - unchanged from before this class gained a second mode).
 * - EXTRA_SHOW_APP_SHARE_CHOICE: show a two-option dialog first (share the app itself vs.
 *   share a direct download link), then share whichever URL the user picked.
 */
class ShareRelayActivity : Activity() {
    companion object {
        const val EXTRA_ARTICLE_URL = "articleUrl"
        const val EXTRA_SHOW_APP_SHARE_CHOICE = "showAppShareChoice"
        private const val APP_URL = "https://github.com/Had-com/NewsFeed-widget"
        private const val DOWNLOAD_URL = "https://github.com/Had-com/NewsFeed-widget/releases/tag/latest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleUrl = intent.getStringExtra(EXTRA_ARTICLE_URL)
        if (!articleUrl.isNullOrBlank()) {
            shareUrl(articleUrl)
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_APP_SHARE_CHOICE, false)) {
            AlertDialog.Builder(this)
                .setTitle("Share NewsFeed")
                .setItems(arrayOf("Share the app", "Share the download link")) { _, which ->
                    shareUrl(if (which == 0) APP_URL else DOWNLOAD_URL)
                    finish()
                }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        finish()
    }

    private fun shareUrl(url: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                "Share",
            )
        )
    }
}
