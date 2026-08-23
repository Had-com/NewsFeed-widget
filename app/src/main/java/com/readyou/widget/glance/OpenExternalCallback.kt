package com.readyou.widget.glance

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.readyou.widget.data.ReadStatusStore
import com.readyou.widget.data.WidgetConfigStore
import kotlinx.coroutines.flow.first

class OpenExternalCallback : ActionCallback {
    companion object {
        val ARTICLE_URL_KEY = ActionParameters.Key<String>("articleUrl")
        val ARTICLE_ID_KEY  = ActionParameters.Key<String>("articleId")
        val WIDGET_ID_KEY   = ActionParameters.Key<Int>("widgetId")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleUrl = parameters[ARTICLE_URL_KEY] ?: return
        val articleId  = parameters[ARTICLE_ID_KEY]  ?: ""
        val widgetId   = parameters[WIDGET_ID_KEY]   ?: return

        val config = WidgetConfigStore(context).configFlow(widgetId).first()

        if (articleId.isNotBlank()) ReadStatusStore(context).markRead(articleId)

        val uri    = Uri.parse(articleUrl)
        val intent = when (config.externalApp) {
            "share" -> Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, articleUrl),
                "Share article",
            )
            else -> Intent(Intent.ACTION_VIEW, uri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }
        WidgetWorker.refreshNow(context)
    }
}
