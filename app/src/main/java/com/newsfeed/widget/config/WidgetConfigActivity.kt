package com.newsfeed.widget.config

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import androidx.core.content.FileProvider
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.FilterMode
import com.newsfeed.widget.data.OpmlManager
import com.newsfeed.widget.data.NewsFeedRepository
import com.newsfeed.widget.data.SortOrder
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetConfigStore
import com.newsfeed.widget.glance.NewsFeedWidget
import com.newsfeed.widget.glance.WidgetWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

class WidgetConfigActivity : ComponentActivity() {

    companion object {
        fun feedAccentColors(theme: String) = paletteForTheme(theme)
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent.extras
            ?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED); finish(); return
        }

        val store = WidgetConfigStore(this)
        val repo  = NewsFeedRepository(this)

        setContent {
            MaterialTheme {
                var config by remember { mutableStateOf(WidgetConfig(widgetId = appWidgetId)) }
                val scope  = rememberCoroutineScope()

                androidx.compose.runtime.LaunchedEffect(appWidgetId) {
                    val saved = store.configFlow(appWidgetId).first()
                    if (saved.feeds.isEmpty()) {
                        val opml = runCatching {
                            assets.open("default_feeds.opml").bufferedReader().readText()
                        }.getOrNull()
                        val defaults = opml?.let { OpmlManager.parse(it) }
                            ?.mapIndexed { i, (title, url) ->
                                FeedConfig(feedId = url, displayName = title, feedUrl = url,
                                    accentColor = feedAccentColors(saved.widgetTheme)[i % feedAccentColors(saved.widgetTheme).size])
                            }
                            ?: emptyList()
                        config = saved.copy(feeds = defaults, feedOrder = defaults.map { it.feedId })
                    } else {
                        config = saved.copy(feedOrder = saved.feedOrder.ifEmpty { saved.feeds.map { it.feedId } })
                    }
                }

                val feedOrder = remember(config.feedOrder) {
                    androidx.compose.runtime.mutableStateListOf(*config.feedOrder.toTypedArray())
                }
                val lazyListState = rememberLazyListState()
                val reorderState  = rememberReorderableLazyListState(lazyListState) { from, to ->
                    // The LazyColumn has 3 non-reorderable header items before the feed rows,
                    // so subtract that offset to get feed-relative indices.
                    val offset = 3
                    val fromIdx = from.index - offset
                    val toIdx   = to.index - offset
                    if (fromIdx >= 0 && toIdx >= 0 && fromIdx < feedOrder.size && toIdx < feedOrder.size) {
                        feedOrder.add(toIdx, feedOrder.removeAt(fromIdx))
                    }
                }

                var showSortMenu     by remember { mutableStateOf(false) }
                var showFilterMenu   by remember { mutableStateOf(false) }
                var showRefreshMenu  by remember { mutableStateOf(false) }
                var showExternalMenu by remember { mutableStateOf(false) }
                var showLengthMenu   by remember { mutableStateOf(false) }
                var showThemeMenu    by remember { mutableStateOf(false) }

                val refreshOptions = listOf(
                    15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour",
                    120 to "2 hours", 240 to "4 hours", 360 to "6 hours", 720 to "12 hours",
                )
                val externalOptions = listOf(
                    "browser"  to "Browser",
                    "share"    to "Share sheet",
                )

                var addFeedUrl    by remember { mutableStateOf("") }
                var isAddingFeed  by remember { mutableStateOf(false) }
                var addFeedError  by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }

                var searchQuery    by remember { mutableStateOf("") }
                var isSearching    by remember { mutableStateOf(false) }
                var searchResults  by remember { mutableStateOf<List<com.newsfeed.widget.data.FeedSearchResult>>(emptyList()) }
                var searchError    by remember { mutableStateOf<String?>(null) }
                var searchDone     by remember { mutableStateOf(false) }

                // Edit-feed dialog state
                var editingFeed   by remember { mutableStateOf<FeedConfig?>(null) }
                var editName      by remember { mutableStateOf("") }
                var editUrl       by remember { mutableStateOf("") }
                var editUrlError  by remember { mutableStateOf<String?>(null) }
                var isEditLoading by remember { mutableStateOf(false) }

                if (editingFeed != null) {
                    AlertDialog(
                        onDismissRequest = { editingFeed = null },
                        title   = { Text("Edit feed") },
                        text    = {
                            Column {
                                OutlinedTextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                    label = { Text("Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = editUrl,
                                    onValueChange = { editUrl = it; editUrlError = null },
                                    label = { Text("Feed URL") },
                                    singleLine = true,
                                    isError = editUrlError != null,
                                    supportingText = editUrlError?.let { e -> { Text(e) } },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isEditLoading,
                                onClick = {
                                    val original = editingFeed ?: return@TextButton
                                    val newUrl   = editUrl.trim().let { if (it.startsWith("http")) it else "https://$it" }
                                    scope.launch {
                                        var updatedName = editName.trim()
                                        if (newUrl != original.feedUrl) {
                                            isEditLoading = true
                                            val fetched = repo.fetchFeedTitle(newUrl)
                                            if (fetched == null) {
                                                editUrlError  = "Could not load feed — check the URL"
                                                isEditLoading = false
                                                return@launch
                                            }
                                            if (updatedName.isBlank()) updatedName = fetched
                                            isEditLoading = false
                                        }
                                        if (updatedName.isBlank()) updatedName = original.displayName
                                        config = config.copy(
                                            feeds = config.feeds.map {
                                                if (it.feedId == original.feedId)
                                                    it.copy(displayName = updatedName, feedUrl = newUrl, feedId = newUrl)
                                                else it
                                            }
                                        )
                                        val idx = feedOrder.indexOf(original.feedId)
                                        if (idx >= 0) feedOrder[idx] = newUrl
                                        editingFeed = null
                                    }
                                },
                            ) {
                                if (isEditLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Save")
                            }
                        },
                        dismissButton = { TextButton(onClick = { editingFeed = null }) { Text("Cancel") } },
                    )
                }

                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri ?: return@rememberLauncherForActivityResult
                    scope.launch {
                        val xml = withContext(Dispatchers.IO) {
                            runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
                        } ?: run { statusMessage = "Could not read file"; return@launch }
                        val parsed   = OpmlManager.parse(xml)
                        val existing = config.feeds.map { it.feedId }.toSet()
                        val toAdd    = parsed
                            .filter { (_, url) -> url !in existing }
                            .map { (title, url) -> FeedConfig(feedId = url, displayName = title, feedUrl = url) }
                        if (toAdd.isNotEmpty()) {
                            config = config.copy(feeds = config.feeds + toAdd)
                            toAdd.forEach { feedOrder.add(it.feedId) }
                            statusMessage = "Added ${toAdd.size} feed(s)"
                        } else {
                            statusMessage = "No new feeds found"
                        }
                    }
                }

                fun doAddFeed() {
                    val raw = addFeedUrl.trim(); if (raw.isBlank()) return
                    val url = if (raw.startsWith("http")) raw else "https://$raw"
                    scope.launch {
                        isAddingFeed = true; addFeedError = null; statusMessage = ""
                        val title = repo.fetchFeedTitle(url)
                        if (title != null) {
                            config = config.copy(feeds = config.feeds + FeedConfig(feedId = url, displayName = title, feedUrl = url))
                            feedOrder.add(url); addFeedUrl = ""
                        } else { addFeedError = "Could not load feed — check the URL" }
                        isAddingFeed = false
                    }
                }

                fun doExport() {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(cacheDir, "opml").also { it.mkdirs() }
                            File(dir, "feeds.opml").also { it.writeText(OpmlManager.export(config.feeds)) }
                        }
                        val uri = FileProvider.getUriForFile(this@WidgetConfigActivity, "${packageName}.fileprovider", file)
                        startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/xml"; putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Export OPML",
                        ))
                    }
                }

                val fontSizeLabel = when {
                    config.fontSize < 0.75f -> "Tiny"
                    config.fontSize < 1.0f  -> "Small"
                    config.fontSize < 1.5f  -> "Medium"
                    config.fontSize < 2.0f  -> "Large"
                    else                    -> "Huge"
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
                                        WidgetWorker.schedule(this@WidgetConfigActivity, final.refreshIntervalMinutes.toLong())
                                        WidgetWorker.refreshNow(this@WidgetConfigActivity)
                                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                                        NewsFeedWidget().update(this@WidgetConfigActivity, glanceId)
                                        setResult(RESULT_OK, Intent().apply { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) })
                                        finish()
                                    }
                                }) { Text("Save") }
                            },
                        )
                    },
                ) { paddingValues ->
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                    ) {
                        // ── Sort, Filter, Refresh, External App, Font Size ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("SORT & FILTER", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))

                                // Sort
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Sort by", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        TextButton(onClick = { showSortMenu = true }) {
                                            Text("${SortOrder.entries.first { it.key == config.sortOrder }.labelRes} ▾", fontSize = 13.sp)
                                        }
                                        DropdownMenu(showSortMenu, { showSortMenu = false }) {
                                            SortOrder.entries.forEach { o ->
                                                DropdownMenuItem(text = { Text(o.labelRes) },
                                                    onClick = { config = config.copy(sortOrder = o.key); showSortMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Filter
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Show", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        TextButton(onClick = { showFilterMenu = true }) {
                                            Text("${FilterMode.entries.first { it.key == config.filter }.labelRes} ▾", fontSize = 13.sp)
                                        }
                                        DropdownMenu(showFilterMenu, { showFilterMenu = false }) {
                                            FilterMode.entries.forEach { m ->
                                                DropdownMenuItem(text = { Text(m.labelRes) },
                                                    onClick = { config = config.copy(filter = m.key); showFilterMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Refresh interval
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Refresh every", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val label = refreshOptions.firstOrNull { it.first == config.refreshIntervalMinutes }?.second
                                            ?: "${config.refreshIntervalMinutes} min"
                                        TextButton(onClick = { showRefreshMenu = true }) { Text("$label ▾", fontSize = 13.sp) }
                                        DropdownMenu(showRefreshMenu, { showRefreshMenu = false }) {
                                            refreshOptions.forEach { (minutes, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(refreshIntervalMinutes = minutes); showRefreshMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Open article in
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Open article in", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val label = externalOptions.firstOrNull { it.first == config.externalApp }?.second ?: "Browser"
                                        TextButton(onClick = { showExternalMenu = true }) { Text("$label ▾", fontSize = 13.sp) }
                                        DropdownMenu(showExternalMenu, { showExternalMenu = false }) {
                                            externalOptions.forEach { (key, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(externalApp = key); showExternalMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Font size slider
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Font size", style = MaterialTheme.typography.bodyMedium)
                                    Text(fontSizeLabel, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Slider(
                                    value = config.fontSize,
                                    onValueChange = { config = config.copy(fontSize = it) },
                                    valueRange = 0.5f..3.0f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                // Live preview
                                val sampleDesc = "פרטי הכתבה לדוגמה מופיעים כאן לאחר הפתיחה — When you tap a headline, this is the description text that appears below it. The length setting controls how much of this text is shown."
                                val previewDesc = when (config.articleLength) {
                                    "short" -> sampleDesc.take(100).trimEnd() + "…"
                                    "full"  -> sampleDesc
                                    else    -> sampleDesc.take(400).trimEnd()
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        "ynet מבזקים  ·  14:30",
                                        fontSize   = (9f * config.fontSize).sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "כותרת כתבה לדוגמה — Sample article headline",
                                        fontSize   = (13f * config.fontSize).sp,
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = (17f * config.fontSize).sp,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        previewDesc,
                                        fontSize   = (10f * config.fontSize).sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = (14f * config.fontSize).sp,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))

                                // Article length (controls how much description is shown when expanded)
                                val lengthOptions = listOf(
                                    "short"  to "Subtitle only",
                                    "medium" to "First paragraph",
                                    "full"   to "Full article",
                                )
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Expanded article", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val lengthLabel = lengthOptions.firstOrNull { it.first == config.articleLength }?.second ?: "First paragraph"
                                        TextButton(onClick = { showLengthMenu = true }) { Text("$lengthLabel ▾", fontSize = 13.sp) }
                                        DropdownMenu(showLengthMenu, { showLengthMenu = false }) {
                                            lengthOptions.forEach { (key, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(articleLength = key); showLengthMenu = false })
                                            }
                                        }
                                    }
                                }

                                // Widget theme
                                val themeOptions = listOf(
                                    "auto"       to "Auto (system)",
                                    "lavender"   to "Lavender",
                                    "amethyst"   to "Amethyst",
                                    "glassy"     to "Glassy",
                                    "simple"     to "Simple",
                                    "aerospace"  to "Aerospace",
                                    "silicon"    to "Data Science",
                                    "glamer"     to "Glamour",
                                )
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Widget theme", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val themeLabel = themeOptions.firstOrNull { it.first == config.widgetTheme }?.second ?: "Auto (system)"
                                        TextButton(onClick = { showThemeMenu = true }) { Text("$themeLabel ▾", fontSize = 13.sp) }
                                        DropdownMenu(showThemeMenu, { showThemeMenu = false }) {
                                            themeOptions.forEach { (key, lbl) ->
                                                DropdownMenuItem(text = { Text(lbl) },
                                                    onClick = { config = config.copy(widgetTheme = key); showThemeMenu = false })
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("Theme variant", style = MaterialTheme.typography.bodyMedium)
                                    Row {
                                        listOf("light" to "Light", "dark" to "Dark").forEach { (key, lbl) ->
                                            val selected = config.themeVariant == key
                                            TextButton(
                                                onClick = { config = config.copy(themeVariant = key) },
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                                        else androidx.compose.ui.graphics.Color.Transparent
                                                    ),
                                            ) {
                                                Text(lbl, fontSize = 13.sp,
                                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                                            else MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column {
                                        Text("Use theme accent colors", style = MaterialTheme.typography.bodyMedium)
                                        Text("Hides per-feed colors; uses the theme palette",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = config.useThemeColors,
                                        onCheckedChange = { config = config.copy(useThemeColors = it) },
                                    )
                                }
                                Column(Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                        Text("Background opacity", style = MaterialTheme.typography.bodyMedium)
                                        Text("${(config.backgroundAlpha * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    Slider(
                                        value = config.backgroundAlpha,
                                        onValueChange = { config = config.copy(backgroundAlpha = it) },
                                        valueRange = 0f..1f,
                                        steps = 19,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Add Feed ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("ADD FEED", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = addFeedUrl,
                                        onValueChange = { addFeedUrl = it; addFeedError = null },
                                        label = { Text("RSS or Atom feed URL") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        isError = addFeedError != null,
                                        supportingText = addFeedError?.let { e -> { Text(e, color = MaterialTheme.colorScheme.error) } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = { doAddFeed() }),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (isAddingFeed) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else TextButton(onClick = { doAddFeed() }, enabled = addFeedUrl.isNotBlank()) { Text("Add") }
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import OPML") }
                                    TextButton(onClick = { doExport() }, enabled = config.feeds.isNotEmpty()) { Text("Export OPML") }
                                }
                                if (statusMessage.isNotEmpty()) {
                                    Text(statusMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Find feeds by search ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("FIND FEEDS", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it; searchDone = false; searchError = null },
                                        label = { Text("Topic, site name, keyword…") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = {
                                            if (searchQuery.isNotBlank() && !isSearching) {
                                                scope.launch {
                                                    isSearching = true; searchError = null; searchDone = false
                                                    val results = repo.searchFeeds(searchQuery)
                                                    searchResults = results
                                                    searchError   = if (results.isEmpty()) "No feeds found" else null
                                                    isSearching   = false; searchDone = true
                                                }
                                            }
                                        }),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (isSearching) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else TextButton(
                                        onClick = {
                                            if (searchQuery.isNotBlank()) scope.launch {
                                                isSearching = true; searchError = null; searchDone = false
                                                val results = repo.searchFeeds(searchQuery)
                                                searchResults = results
                                                searchError   = if (results.isEmpty()) "No feeds found" else null
                                                isSearching   = false; searchDone = true
                                            }
                                        },
                                        enabled = searchQuery.isNotBlank(),
                                    ) { Text("Search") }
                                }
                                if (searchError != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(searchError!!, fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (searchDone && searchResults.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                        searchResults.forEach { result ->
                                            val alreadyAdded = config.feeds.any { it.feedId == result.feedUrl }
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(result.title,
                                                        style = MaterialTheme.typography.bodyMedium)
                                                    if (result.description.isNotBlank()) {
                                                        Text(result.description.take(80),
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                    if (result.subscribers > 0) {
                                                        Text("${result.subscribers} subscribers",
                                                            fontSize = 10.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                                    }
                                                }
                                                TextButton(
                                                    onClick = {
                                                        if (!alreadyAdded) {
                                                            val newFeed = FeedConfig(
                                                                feedId      = result.feedUrl,
                                                                displayName = result.title,
                                                                feedUrl     = result.feedUrl,
                                                                accentColor = feedAccentColors(config.widgetTheme)[config.feeds.size % feedAccentColors(config.widgetTheme).size],
                                                            )
                                                            config = config.copy(feeds = config.feeds + newFeed)
                                                            feedOrder.add(result.feedUrl)
                                                        }
                                                    },
                                                    enabled = !alreadyAdded,
                                                ) { Text(if (alreadyAdded) "Added" else "+ Add") }
                                            }
                                            HorizontalDivider(thickness = 0.5.dp)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }

                        // ── Feed order header ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("FEED ORDER & STYLE", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Text("Tap name to edit  ·  Drag to reorder  ·  × to remove", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                        }

                        // ── Per-feed rows ──
                        items(count = feedOrder.size, key = { feedOrder[it] }) { index ->
                            val feedId     = feedOrder[index]
                            val feedConfig = config.feeds.firstOrNull { it.feedId == feedId } ?: return@items

                            ReorderableItem(reorderState, key = feedId) {
                                Column {
                                    FeedConfigRow(
                                        feedConfig    = feedConfig,
                                        useThemeColors = config.useThemeColors,
                                        theme         = config.widgetTheme,
                                        onUpdate      = { updated ->
                                            config = config.copy(feeds = config.feeds.map { if (it.feedId == updated.feedId) updated else it })
                                        },
                                        onRemove      = {
                                            feedOrder.remove(feedId)
                                            config = config.copy(feeds = config.feeds.filter { it.feedId != feedId })
                                        },
                                        onEditRequest = {
                                            editName     = feedConfig.displayName
                                            editUrl      = feedConfig.feedUrl
                                            editUrlError = null
                                            editingFeed  = feedConfig
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
