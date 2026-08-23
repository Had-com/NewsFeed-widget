package com.newsfeed.widget.config

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

private val THEME_PALETTES: Map<String, List<String>> = mapOf(
    "lavender"  to listOf("#9B72E3","#B39DDB","#7B52C3","#6650A4","#C4AEFF","#A991D4","#8B6FBE","#D0BCFF","#E040FB","#7C4DFF","#5E35B1","#4527A0"),
    "amethyst"  to listOf("#B39DDB","#9B72E3","#7B52C3","#CE93D8","#AB47BC","#8E24AA","#6A1B9A","#4A148C","#EA80FC","#E040FB","#9C27B0","#673AB7"),
    "aerospace" to listOf("#F5A623","#FF8C00","#FFB74D","#FFC107","#FF6D00","#E65100","#FF9800","#FF5722","#FFCC02","#BF360C","#F57C00","#E64A19"),
    "silicon"   to listOf("#00C4B4","#00BCD4","#009E94","#26C6DA","#0097A7","#00838F","#004D40","#80CBC4","#00E5FF","#1DE9B6","#00897B","#006064"),
    "glassy"    to listOf("#4FC3F7","#29B6F6","#0288D1","#81D4FA","#039BE5","#01579B","#00B0FF","#40C4FF","#4DD0E1","#26C6DA","#0097A7","#006064"),
    "simple"    to listOf("#9E9E9E","#757575","#616161","#424242","#BDBDBD","#212121","#607D8B","#455A64","#546E7A","#78909C","#90A4AE","#B0BEC5"),
    "glamer"    to listOf("#F48FB1","#EC407A","#E91E63","#AD1457","#F06292","#FF80AB","#FF4081","#C2185B","#F8BBD0","#FCE4EC","#EF9A9A","#FFCDD2"),
    "auto"      to listOf("#9B72E3","#E35272","#2E9EE3","#E3A042","#2DB888","#E372C4","#3DD4C8","#8BC34A","#E37272","#5472E3","#795548","#607D8B"),
)

fun paletteForTheme(theme: String) = THEME_PALETTES[theme] ?: THEME_PALETTES["auto"]!!

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerGrid(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    theme: String = "auto",
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paletteForTheme(theme).forEach { hex ->
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
