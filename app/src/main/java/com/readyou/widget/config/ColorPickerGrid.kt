package com.readyou.widget.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val PRESET_COLORS = listOf(
    "#9B72E3", // violet
    "#6750A4", // purple
    "#2980b9", // blue
    "#1abc9c", // teal
    "#27ae60", // green
    "#f39c12", // amber
    "#e67e22", // orange
    "#c0392b", // red
    "#e91e63", // pink
    "#607d8b", // blue-grey
    "#795548", // brown
    "#455a64", // dark grey
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerGrid(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PRESET_COLORS.forEach { hex ->
            val parsed = runCatching { Color(android.graphics.Color.parseColor(hex)) }
                .getOrDefault(Color.Gray)
            val isSelected = hex.equals(selectedColor, ignoreCase = true)

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(parsed)
                    .then(
                        if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                        else Modifier
                    )
                    .clickable { onColorSelected(hex) }
            )
        }
    }
}
