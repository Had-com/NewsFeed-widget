package com.newsfeed.widget.update

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.newsfeed.widget.data.ReleaseNotesStore
import kotlinx.coroutines.launch

/**
 * Tap target for the "Update available" notification (UpdateManager.notifyUpdateAvailable).
 * Re-checks fresh rather than threading the notification's already-known version through
 * Intent extras — cheap, and avoids installing a build that's since been superseded by an
 * even newer one. A real, visible Compose screen (not the invisible relay this used to be):
 * it must work standalone, since the app may not already be open when the notification is
 * tapped, so there's no existing screen to show a confirmation dialog over.
 */
class UpdateRelayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                UpdateRelayScreen(onFinished = { finish() })
            }
        }
    }
}

@Composable
private fun UpdateRelayScreen(onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<UpdateManager.UpdateCheckResult?>(null) }
    var notes  by remember { mutableStateOf<List<ReleaseNote>>(emptyList()) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val checkResult = UpdateManager.checkForUpdate(context)
        result = checkResult
        if (checkResult is UpdateManager.UpdateCheckResult.Available) {
            val lastSeenId = ReleaseNotesStore.lastSeenId(context)
            notes = ReleaseNotesFetcher.fetchUnseenNotes(lastSeenId)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val r = result) {
            null -> CircularProgressIndicator()
            UpdateManager.UpdateCheckResult.CheckFailed -> {
                Text("Couldn't check for updates — try again later")
                Button(onClick = onFinished) { Text("Close") }
            }
            UpdateManager.UpdateCheckResult.UpToDate -> {
                Text("You're already up to date")
                Button(onClick = onFinished) { Text("Close") }
            }
            is UpdateManager.UpdateCheckResult.Available -> {
                ReleaseNotesContent(r.versionCode, notes)
                val highestNoteId = notes.maxOfOrNull { it.id }
                Button(onClick = {
                    scope.launch {
                        ReleaseNotesStore.markSeen(context, highestNoteId ?: ReleaseNotesStore.lastSeenId(context))
                        UpdateManager.proceedWithUpdate(context)
                        onFinished()
                    }
                }) { Text("Update Now") }
                Button(onClick = {
                    scope.launch {
                        ReleaseNotesStore.markSeen(context, highestNoteId ?: ReleaseNotesStore.lastSeenId(context))
                        onFinished()
                    }
                }) { Text("Later") }
            }
        }
    }
}
