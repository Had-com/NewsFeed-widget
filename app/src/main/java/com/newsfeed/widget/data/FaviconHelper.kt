package com.newsfeed.widget.data

import android.content.Context
import java.io.File

object FaviconHelper {
    fun file(context: Context, feedId: String): File =
        File(context.cacheDir, "favicons/favicon_${feedId.hashCode()}.png")
}
