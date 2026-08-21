package com.readyou.widget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.readyou.widget.data.FeedConfig
import com.readyou.widget.data.FilterMode
import com.readyou.widget.data.ReadYouRepository
import com.readyou.widget.data.SortOrder
import com.readyou.widget.data.WidgetConfig
import com.readyou.widget.data.WidgetConfigStore
import com.readyou.widget.glance.ReadYouWidget
import com.readyou.widget.glance.WidgetWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Return cancelled if no valid widget id
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val store = WidgetConfigStore(this)
        val repo = ReadYouRepository(this)

        setContent {
            MaterialTheme {
                var config by remember { mutableStateOf(WidgetConfig(widgetId = appWidgetId)) }
                val scope = rememberCoroutineScope()

                // Load existing config + merge with available feeds on first composition
                androidx.compose.runtime.LaunchedEffect(appWidgetId) {
                    val saved = store.configFlow(appWidgetId).first()
                    val availableFeeds = repo.getFeeds()
                    // Merge: preserve saved configs, add new feeds with defaults
                    val mergedFeeds = availableFeeds.map { available ->
                        saved.feeds.firstOrNull { it.feedId == available.feedId } ?: available
                    }
                    val orderedIds = saved.feedOrder.ifEmpty { mergedFeeds.map { it.feedId } }
                    config = saved.copy(feeds = mergedFeeds, feedOrder = orderedIds)
                }

                // Reorderable list state
                val feedOrder = remember(config.feedOrder) {
                    androidx.compose.runtime.mutableStateListOf(*config.feedOrder.toTypedArray())
                }
                val lazyListState = rememberLazyListState()
                val reorderState = rememberReorderableLazyListState(lazyListState, onMove = { from, to ->
                    feedOrder.apply { add(to.index, removeAt(from.index)) }
                })

                var showSortMenu by remember { mutableStateOf(false) }
                var showFilterMenu by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Widget settings") },
                            actions = {
                                TextButton(onClick = {
                                    val final = config.copy(feedOrder = feedOrder.toList())
                                    scope.launch {
                                        store.save(final)
                                        WidgetWorker.schedule(this@WidgetConfigActivity)
                                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity)
                                            .getGlanceIdBy(appWidgetId)
                                        ReadYouWidget().update(this@WidgetConfigActivity, glanceId)
                                        setResult(RESULT_OK, Intent().apply {
                                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                        })
                                        finish()
                                    }
                                }) {
                                    Text("Save")
                                }
                            },
                        )
                    },
                ) { paddingValues ->
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                    ) {
                        // ── Sort & Filter ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    "SORT & FILTER",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.05.sp,
                                )
                                Spacer(Modifier.height(8.dp))

                                // Sort
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Sort by", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val sortLabel = SortOrder.entries.first { it.key == config.sortOrder }.labelRes
                                        TextButton(onClick = { showSortMenu = true }) { Text("$sortLabel ▾", fontSize = 13.sp) }
                                        DropdownMenu(showSortMenu, { showSortMenu = false }) {
                                            SortOrder.entries.forEach { order ->
                                                DropdownMenuItem(
                                                    text = { Text(order.labelRes) },
                                                    onClick = {
                                                        config = config.copy(sortOrder = order.key)
                                                        showSortMenu = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }

                                // Filter
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Show", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val filterLabel = FilterMode.entries.first { it.key == config.filter }.labelRes
                                        TextButton(onClick = { showFilterMenu = true }) { Text("$filterLabel ▾", fontSize = 13.sp) }
                                        DropdownMenu(showFilterMenu, { showFilterMenu = false }) {
                                            FilterMode.entries.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode.labelRes) },
                                                    onClick = {
                                                        config = config.copy(filter = mode.key)
                                                        showFilterMenu = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Feed order & per-feed config header ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    "FEED ORDER & STYLE",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.05.sp,
                                )
                                Text(
                                    "Drag rows to reorder",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }

                        // ── Per-feed rows (draggable) ──
                        items(
                            count = feedOrder.size,
                            key = { feedOrder[it] },
                        ) { index ->
                            val feedId = feedOrder[index]
                            val feedConfig = config.feeds.firstOrNull { it.feedId == feedId }
                                ?: return@items

                            ReorderableItem(reorderState, key = feedId) {
                                Column {
                                    FeedConfigRow(
                                        feedConfig = feedConfig,
                                        onUpdate = { updated ->
                                            config = config.copy(
                                                feeds = config.feeds.map {
                                                    if (it.feedId == updated.feedId) updated else it
                                                },
                                            )
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
