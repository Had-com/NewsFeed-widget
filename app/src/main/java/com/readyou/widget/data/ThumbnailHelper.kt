package com.readyou.widget.data

import android.content.Context
import java.io.File

object ThumbnailHelper {
    fun file(context: Context, articleId: String): File =
        File(context.cacheDir, "thumbs/thumb_${articleId.hashCode()}.jpg")
}
