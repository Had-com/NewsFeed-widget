package com.newsfeed.widget.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The shared "here's what's new" body shown before installing a self-update — used by both
 * WidgetConfigActivity's in-app AlertDialog (Task 6) and UpdateRelayActivity's full-screen
 * confirmation (Task 7), so the actual content is defined once. Callers own the surrounding
 * chrome (dialog vs. screen, buttons) and pass in the notes to show.
 */
@Composable
fun ReleaseNotesContent(versionCode: Int, notes: List<ReleaseNote>) {
    Column {
        Text("Build $versionCode is ready to install.", style = MaterialTheme.typography.bodyMedium)
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("What's new:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            for (note in notes) {
                for (bullet in note.bullets) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodySmall)
                        Text(bullet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
