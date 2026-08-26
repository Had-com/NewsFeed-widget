package com.newsfeed.widget.glance

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * A share button tapped from the widget can't call Intent.createChooser() + startActivity()
 * directly and reliably — Glance's actionStartActivity() only reliably launches a single,
 * unambiguous target from a widget's PendingIntent context (confirmed: plain ACTION_VIEW
 * works, but ACTION_SEND with a chooser or multiple share targets does not, even once each
 * row's intent is made distinct to avoid Glance's action-conflation). This tiny invisible
 * relay activity is itself a single, unambiguous target Glance can launch cleanly; once it's
 * actually running as a real foreground Activity, it has full standing to build and launch the
 * chooser normally, the same way any ordinary "Share" button in a full app would.
 */
class ShareRelayActivity : Activity() {
    companion object {
        const val EXTRA_ARTICLE_URL = "articleUrl"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_ARTICLE_URL)
        if (!url.isNullOrBlank()) {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                    "Share article",
                )
            )
        }
        finish()
    }
}
