package com.newsfeed.widget.update

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tap target for the "Update available" notification (UpdateManager.notifyUpdateAvailable).
 * Re-checks fresh rather than threading the notification's already-known version through
 * Intent extras — cheap, and avoids installing a build that's since been superseded by an
 * even newer one. Same invisible-relay-activity shape as ShareRelayActivity: exists because
 * the download (a real network call, possibly several seconds) needs a real Activity
 * lifecycle to run from — a BroadcastReceiver's goAsync() has a hard ~10 second budget.
 */
class UpdateRelayActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            UpdateManager.checkAndUpdate(applicationContext, notifyOnly = false)
            finish()
        }
    }
}
