package com.readyou.widget.config

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readyou.widget.R
import com.readyou.widget.data.FeedConfig
import sh.calvin.reorderable.ReorderableCollectionItemScope

@Composable
fun ReorderableCollectionItemScope.FeedConfigRow(
    feedConfig: FeedConfig,
    onUpdate: (FeedConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    var showFontMenu by remember { mutableStateOf(false) }

    val accentColor = runCatching {
        Color(android.graphics.Color.parseColor(feedConfig.accentColor))
    }.getOrDefault(Color(0xFF9B72E3))

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Top row: drag handle | swatch | name | RTL/LTR toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_drag_handle),
                contentDescription = "Drag to reorder",
                modifier = Modifier
                    .size(20.dp)
                    .draggableHandle(),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )

            Spacer(Modifier.width(8.dp))

            // Color swatch — tap to open picker
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accentColor)
                    .clickable { showColorPicker = !showColorPicker }
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = feedConfig.displayName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )

            // RTL / LTR toggle
            val isRtl = feedConfig.layoutDirection == "rtl"
            val dirLabel = if (isRtl) "RTL" else "LTR"
            val dirBorderColor by animateColorAsState(
                if (isRtl) Color(0xFF6750A4) else MaterialTheme.colorScheme.outline,
                label = "dirBorder",
            )
            Text(
                text = dirLabel,
                fontSize = 11.sp,
                color = if (isRtl) Color(0xFFC4A9FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .border(0.5.dp, dirBorderColor, RoundedCornerShape(6.dp))
                    .clickable { onUpdate(feedConfig.copy(layoutDirection = if (isRtl) "ltr" else "rtl")) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // Color picker (inline, shown on tap)
        if (showColorPicker) {
            Spacer(Modifier.height(8.dp))
            ColorPickerGrid(
                selectedColor = feedConfig.accentColor,
                onColorSelected = {
                    onUpdate(feedConfig.copy(accentColor = it))
                    showColorPicker = false
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Bottom row: font selector | B / I / U toggles
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Font dropdown
            Box {
                val fontLabel = when (feedConfig.fontFamily) {
                    "serif" -> "Serif"
                    "mono" -> "Mono"
                    else -> "Default"
                }
                Text(
                    text = "$fontLabel ▾",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                        .clickable { showFontMenu = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = showFontMenu, onDismissRequest = { showFontMenu = false }) {
                    listOf("sans" to "Default", "serif" to "Serif", "mono" to "Mono").forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onUpdate(feedConfig.copy(fontFamily = key))
                                showFontMenu = false
                            },
                        )
                    }
                }
            }

            // B / I / U toggles
            StyleToggle("B", "bold", FontWeight.Bold, feedConfig, onUpdate)
            StyleToggle("I", "italic", null, feedConfig, onUpdate, fontStyle = FontStyle.Italic)
            StyleToggle("U", "underline", null, feedConfig, onUpdate, textDecoration = TextDecoration.Underline)
        }
    }
}

@Composable
private fun StyleToggle(
    label: String,
    styleKey: String,
    fontWeight: FontWeight? = null,
    feedConfig: FeedConfig,
    onUpdate: (FeedConfig) -> Unit,
    fontStyle: FontStyle? = null,
    textDecoration: TextDecoration? = null,
) {
    val isOn = styleKey in feedConfig.textStyle
    val bgColor by animateColorAsState(
        if (isOn) Color(0x296750A4) else Color.Transparent,
        label = "styleBg",
    )
    val borderColor by animateColorAsState(
        if (isOn) Color(0xFF6750A4) else Color(0xFF666666),
        label = "styleBorder",
    )
    Text(
        text = label,
        fontSize = 12.sp,
        fontWeight = fontWeight ?: FontWeight.Normal,
        fontStyle = fontStyle ?: FontStyle.Normal,
        textDecoration = textDecoration ?: TextDecoration.None,
        color = if (isOn) Color(0xFFC4A9FF) else Color(0xFF888888),
        modifier = Modifier
            .size(28.dp, 24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bgColor)
            .border(0.5.dp, borderColor, RoundedCornerShape(5.dp))
            .clickable {
                val updated = if (isOn) feedConfig.textStyle - styleKey else feedConfig.textStyle + styleKey
                onUpdate(feedConfig.copy(textStyle = updated))
            }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
