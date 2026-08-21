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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
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

                // Load saved config on first composition
                androidx.compose.runtime.LaunchedEffect(appWidgetId) {
                    val saved = store.configFlow(appWidgetId).first()
                    val order = saved.feedOrder.ifEmpty { saved.feeds.map { it.feedId } }
                    config = saved.copy(feedOrder = order)
                }

                val feedOrder = remember(config.feedOrder) {
                    androidx.compose.runtime.mutableStateListOf(*config.feedOrder.toTypedArray())
                }
                val lazyListState = rememberLazyListState()
                val reorderState = rememberReorderableLazyListState(lazyListState, onMove = { from, to ->
                    feedOrder.apply { add(to.index, removeAt(from.index)) }
                })

                var showSortMenu by remember { mutableStateOf(false) }
                var showFilterMenu by remember { mutableStateOf(false) }
                var addFeedUrl by remember { mutableStateOf("") }
                var isAddingFeed by remember { mutableStateOf(false) }
                var addFeedError by remember { mutableStateOf<String?>(null) }

                fun doAddFeed() {
                    val raw = addFeedUrl.trim()
                    if (raw.isBlank()) return
                    val url = if (raw.startsWith("http")) raw else "https://$raw"
                    scope.launch {
                        isAddingFeed = true
                        addFeedError = null
                        val title = repo.fetchFeedTitle(url)
                        if (title != null) {
                            val newFeed = FeedConfig(
                                feedId = url,
                                displayName = title,
                                feedUrl = url,
                            )
                            config = config.copy(feeds = config.feeds + newFeed)
                            feedOrder.add(url)
                            addFeedUrl = ""
                        } else {
                            addFeedError = "Could not load feed — check the URL"
                        }
                        isAddingFeed = false
                    }
                }

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
                                }) { Text("Save") }
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
                                                    onClick = { config = config.copy(sortOrder = order.key); showSortMenu = false },
                                                )
                                            }
                                        }
                                    }
                                }
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
                                                    onClick = { config = config.copy(filter = mode.key); showFilterMenu = false },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Add Feed ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    "ADD FEED",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.05.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = addFeedUrl,
                                        onValueChange = { addFeedUrl = it; addFeedError = null },
                                        label = { Text("RSS or Atom feed URL") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        isError = addFeedError != null,
                                        supportingText = addFeedError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(onDone = { doAddFeed() }),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (isAddingFeed) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        TextButton(
                                            onClick = { doAddFeed() },
                                            enabled = addFeedUrl.isNotBlank(),
                                        ) { Text("Add") }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Feed order & style header ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    "FEED ORDER & STYLE",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.05.sp,
                                )
                                Text(
                                    "Drag to reorder  ·  × to remove",
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
                                        onRemove = {
                                            feedOrder.removeAt(index)
                                            config = config.copy(
                                                feeds = config.feeds.filter { it.feedId != feedId },
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
