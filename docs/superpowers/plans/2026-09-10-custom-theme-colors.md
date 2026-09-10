# Custom Font/Background Color Theme Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users pick their own font color and background color for the whole widget as a 10th theme option ("Custom"), with the dark variant automatically swapping the two colors.

**Architecture:** Two new `WidgetConfig` string fields carry the hex colors. `WidgetThemes.kt`'s three existing theme-resolution functions grow an early-return branch for `theme == "custom"` that builds a `ColorScheme` from those two colors (swapped for dark variant, secondary colors derived by opacity), via a new Android-framework-free hex parser kept separately testable. `WidgetConfigActivity.kt` adds "Custom" to the theme picker and two hex input fields shown only when it's selected.

**Tech Stack:** Kotlin, Jetpack Compose/Glance (existing), JUnit 4 (already added by the Telegram feature).

---

## Design note on testability (read before starting)

`android.graphics.Color.parseColor()` throws `RuntimeException: Method parseColor not
mocked` in this project's plain JUnit tests (no Robolectric, deliberately never added — same
reason the Telegram feature avoided `Html.fromHtml()`). Task 1 below writes a small
hand-written `WidgetThemes.parseHexColor()` instead, which is plain Kotlin (string slicing +
`Long.toInt()`) with no Android framework call inside it, and is fully unit-testable.
`androidx.compose.ui.graphics.Color`'s own `Color(Int)` constructor is pure Kotlin bit
arithmetic — safe to construct directly in a unit test.

---

## File Structure

- **Modify:** `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt` — two new
  `WidgetConfig` fields.
- **Modify:** `app/src/main/java/com/newsfeed/widget/glance/WidgetThemes.kt` — new
  `parseHexColor()`, new `customColorScheme()`, and the three existing resolver functions
  extended with two new defaulted parameters each.
- **Create:** `app/src/test/java/com/newsfeed/widget/glance/WidgetThemesTest.kt` — JUnit 4
  tests for `parseHexColor()` and the `"custom"` theme branch of the three resolver
  functions.
- **Modify:** `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` —
  "Custom" theme option, two hex color inputs, and the one call site
  (`rawColorSchemeFor`) updated to pass the new config fields through.
- **Modify:** `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt` — the two
  call sites (`colorProvidersFor`, `surfaceColorFor`) updated the same way.
- **Modify:** `docs/BUGS.md` — feature entry once verified.

---

### Task 1: `WidgetConfig` fields

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt`

No unit tests needed for this task alone — it's two new fields on an existing plain data
class with default values, exercised indirectly by every test in later tasks that constructs
or reads a `WidgetConfig`.

- [ ] **Step 1: Add the two fields and update the `widgetTheme` comment**

In `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt`, find:

```kotlin
    val widgetTheme: String = "glamer",        // "auto" | "lavender" | "amethyst" | "glassy" | "simple" | "aerospace" | "silicon" | "glamer" | "blackwhite"
```

Replace with:

```kotlin
    val widgetTheme: String = "glamer",        // "auto" | "lavender" | "amethyst" | "glassy" | "simple" | "aerospace" | "silicon" | "glamer" | "blackwhite" | "custom"
    val customFontColor: String = "#1B1F27",       // used only when widgetTheme == "custom"
    val customBackgroundColor: String = "#FFFFFF", // dark-on-light default so it looks
                                                    // reasonable before the user changes it
```

- [ ] **Step 2: Verify the project compiles**

Local Gradle is not runnable in this sandbox (`gradle/wrapper/gradle-wrapper.jar` is missing,
no system-wide `gradle` binary — the same known, accepted limitation throughout this
project). Hand-verify: re-read the edited region for correct Kotlin syntax (trailing comma
placement, comment alignment) and confirm no other field in the data class was disturbed.
Report `DONE_WITH_CONCERNS` for the inability to compile — expected, not a blocker.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt
git commit -m "Add customFontColor/customBackgroundColor fields to WidgetConfig"
```

---

### Task 2: `WidgetThemes.parseHexColor()` and the `"custom"` theme branch

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/WidgetThemes.kt`
- Create: `app/src/test/java/com/newsfeed/widget/glance/WidgetThemesTest.kt`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/newsfeed/widget/glance/WidgetThemesTest.kt`:

```kotlin
package com.newsfeed.widget.glance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetThemesTest {

    @Test
    fun `parseHexColor parses a 6-digit hex with leading hash`() {
        val color = WidgetThemes.parseHexColor("#FF0000")
        assertEquals(0xFFFF0000.toInt(), color?.toArgb())
    }

    @Test
    fun `parseHexColor parses a 6-digit hex without leading hash`() {
        val color = WidgetThemes.parseHexColor("00FF00")
        assertEquals(0xFF00FF00.toInt(), color?.toArgb())
    }

    @Test
    fun `parseHexColor parses an 8-digit ARGB hex`() {
        val color = WidgetThemes.parseHexColor("#800000FF")
        assertEquals(0x800000FF.toInt(), color?.toArgb())
    }

    @Test
    fun `parseHexColor returns null for the wrong length`() {
        assertNull(WidgetThemes.parseHexColor("#ABC"))
        assertNull(WidgetThemes.parseHexColor(""))
    }

    @Test
    fun `parseHexColor returns null for non-hex characters`() {
        assertNull(WidgetThemes.parseHexColor("#GGGGGG"))
    }

    @Test
    fun `rawColorSchemeFor custom light uses background and font colors as picked`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "light",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        assertEquals(WidgetThemes.parseHexColor("#EEEEEE"), scheme.background)
        assertEquals(WidgetThemes.parseHexColor("#111111"), scheme.onSurface)
    }

    @Test
    fun `rawColorSchemeFor custom dark swaps background and font colors`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "dark",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        assertEquals(WidgetThemes.parseHexColor("#111111"), scheme.background)
        assertEquals(WidgetThemes.parseHexColor("#EEEEEE"), scheme.onSurface)
    }

    @Test
    fun `rawColorSchemeFor custom falls back to defaults for invalid hex`() {
        val scheme = WidgetThemes.rawColorSchemeFor(
            theme = "custom", variant = "light",
            customFont = "not-a-color", customBackground = "also-not-a-color",
        )
        // Falls back to the same defaults FeedConfig.kt's WidgetConfig fields use.
        assertEquals(WidgetThemes.parseHexColor("#FFFFFF"), scheme.background)
        assertEquals(WidgetThemes.parseHexColor("#1B1F27"), scheme.onSurface)
    }

    @Test
    fun `surfaceColorFor custom matches rawColorSchemeFor's surface`() {
        val surface = WidgetThemes.surfaceColorFor(
            theme = "custom", variant = "light",
            customFont = "#111111", customBackground = "#EEEEEE",
        )
        assertEquals(WidgetThemes.parseHexColor("#EEEEEE"), surface)
    }

    @Test
    fun `existing themes are unaffected by the new parameters' defaults`() {
        // A caller that never passes customFont/customBackground (every pre-existing call
        // site, until Tasks 3-4 update them) must still resolve exactly as before for any
        // non-custom theme - the new parameters must be inert unless theme == "custom".
        val before = WidgetThemes.rawColorSchemeFor("glamer", "dark")
        val after = WidgetThemes.rawColorSchemeFor("glamer", "dark", "#000000", "#FFFFFF")
        assertEquals(before.background, after.background)
        assertEquals(before.onSurface, after.onSurface)
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.glance.WidgetThemesTest"`
Expected: FAIL to compile — `parseHexColor` doesn't exist yet, and `rawColorSchemeFor`/
`surfaceColorFor` don't accept `customFont`/`customBackground` yet.

(Local Gradle is not runnable in this sandbox — hand-trace every step in this task instead,
exactly as every other task in this project has, and report `DONE_WITH_CONCERNS` for that
reason. Expected, not a blocker.)

- [ ] **Step 3: Implement `parseHexColor()`, `customColorScheme()`, and extend the three resolvers**

In `app/src/main/java/com/newsfeed/widget/glance/WidgetThemes.kt`, find:

```kotlin
    fun rawColorSchemeFor(theme: String, variant: String = "dark"): ColorScheme {
        val dark = variant == "dark"
        return when (theme) {
            "lavender", "light"       -> if (dark) LAVENDER_DARK   else LAVENDER_LIGHT
            "amethyst", "dark"        -> if (dark) AMETHYST_DARK   else AMETHYST_LIGHT
            "glassy"                  -> if (dark) GLASSY_DARK     else GLASSY_LIGHT
            "simple"                  -> if (dark) SIMPLE_DARK     else SIMPLE_LIGHT
            "aerospace"               -> if (dark) AEROSPACE_DARK  else AEROSPACE_LIGHT
            "silicon", "data_science" -> if (dark) SILICON_DARK    else SILICON_LIGHT
            "glamer"                  -> if (dark) GLAMER_DARK     else GLAMER_LIGHT
            "blackwhite"              -> if (dark) BLACKWHITE_DARK else BLACKWHITE_LIGHT
            else                      -> if (dark) darkColorScheme() else lightColorScheme()
        }
    }

    fun surfaceColorFor(theme: String, variant: String = "dark"): Color {
        val dark = variant == "dark"
        return when (theme) {
            "lavender", "light"       -> if (dark) LAVENDER_DARK.surface   else LAVENDER_LIGHT.surface
            "amethyst", "dark"        -> if (dark) AMETHYST_DARK.surface   else AMETHYST_LIGHT.surface
            "glassy"                  -> if (dark) GLASSY_DARK.surface     else GLASSY_LIGHT.surface
            "simple"                  -> if (dark) SIMPLE_DARK.surface     else SIMPLE_LIGHT.surface
            "aerospace"               -> if (dark) AEROSPACE_DARK.surface  else AEROSPACE_LIGHT.surface
            "silicon", "data_science" -> if (dark) SILICON_DARK.surface    else SILICON_LIGHT.surface
            "glamer"                  -> if (dark) GLAMER_DARK.surface     else GLAMER_LIGHT.surface
            "blackwhite"              -> if (dark) BLACKWHITE_DARK.surface else BLACKWHITE_LIGHT.surface
            else                      -> if (dark) Color(0xFF1C1B1F)       else Color(0xFFFFFFFF)
        }
    }

    fun colorProvidersFor(theme: String, variant: String = "dark"): ColorProviders {
        val dark = variant == "dark"
        return when (theme) {
            "lavender", "light"       -> if (dark) buildColorProviders(light = LAVENDER_DARK,    dark = LAVENDER_DARK)
                                         else      buildColorProviders(light = LAVENDER_LIGHT,   dark = LAVENDER_LIGHT)
            "amethyst", "dark"        -> if (dark) buildColorProviders(light = AMETHYST_DARK,    dark = AMETHYST_DARK)
                                         else      buildColorProviders(light = AMETHYST_LIGHT,   dark = AMETHYST_LIGHT)
            "glassy"                  -> if (dark) buildColorProviders(light = GLASSY_DARK,      dark = GLASSY_DARK)
                                         else      buildColorProviders(light = GLASSY_LIGHT,     dark = GLASSY_LIGHT)
            "simple"                  -> if (dark) buildColorProviders(light = SIMPLE_DARK,      dark = SIMPLE_DARK)
                                         else      buildColorProviders(light = SIMPLE_LIGHT,     dark = SIMPLE_LIGHT)
            "aerospace"               -> if (dark) buildColorProviders(light = AEROSPACE_DARK,   dark = AEROSPACE_DARK)
                                         else      buildColorProviders(light = AEROSPACE_LIGHT,  dark = AEROSPACE_LIGHT)
            "silicon", "data_science" -> if (dark) buildColorProviders(light = SILICON_DARK,     dark = SILICON_DARK)
                                         else      buildColorProviders(light = SILICON_LIGHT,    dark = SILICON_LIGHT)
            "glamer"                  -> if (dark) buildColorProviders(light = GLAMER_DARK,      dark = GLAMER_DARK)
                                         else      buildColorProviders(light = GLAMER_LIGHT,     dark = GLAMER_LIGHT)
            "blackwhite"              -> if (dark) buildColorProviders(light = BLACKWHITE_DARK,  dark = BLACKWHITE_DARK)
                                         else      buildColorProviders(light = BLACKWHITE_LIGHT, dark = BLACKWHITE_LIGHT)
            else                      -> buildColorProviders(light = lightColorScheme(), dark = darkColorScheme()) // "auto"
        }
    }
```

Replace all three functions with (only the signature and first line of each function body
changed — every existing branch is untouched):

```kotlin
    fun rawColorSchemeFor(
        theme: String,
        variant: String = "dark",
        customFont: String = "#1B1F27",
        customBackground: String = "#FFFFFF",
    ): ColorScheme {
        if (theme == "custom") return customColorScheme(variant, customFont, customBackground)
        val dark = variant == "dark"
        return when (theme) {
            "lavender", "light"       -> if (dark) LAVENDER_DARK   else LAVENDER_LIGHT
            "amethyst", "dark"        -> if (dark) AMETHYST_DARK   else AMETHYST_LIGHT
            "glassy"                  -> if (dark) GLASSY_DARK     else GLASSY_LIGHT
            "simple"                  -> if (dark) SIMPLE_DARK     else SIMPLE_LIGHT
            "aerospace"               -> if (dark) AEROSPACE_DARK  else AEROSPACE_LIGHT
            "silicon", "data_science" -> if (dark) SILICON_DARK    else SILICON_LIGHT
            "glamer"                  -> if (dark) GLAMER_DARK     else GLAMER_LIGHT
            "blackwhite"              -> if (dark) BLACKWHITE_DARK else BLACKWHITE_LIGHT
            else                      -> if (dark) darkColorScheme() else lightColorScheme()
        }
    }

    fun surfaceColorFor(
        theme: String,
        variant: String = "dark",
        customFont: String = "#1B1F27",
        customBackground: String = "#FFFFFF",
    ): Color {
        if (theme == "custom") return customColorScheme(variant, customFont, customBackground).surface
        val dark = variant == "dark"
        return when (theme) {
            "lavender", "light"       -> if (dark) LAVENDER_DARK.surface   else LAVENDER_LIGHT.surface
            "amethyst", "dark"        -> if (dark) AMETHYST_DARK.surface   else AMETHYST_LIGHT.surface
            "glassy"                  -> if (dark) GLASSY_DARK.surface     else GLASSY_LIGHT.surface
            "simple"                  -> if (dark) SIMPLE_DARK.surface     else SIMPLE_LIGHT.surface
            "aerospace"               -> if (dark) AEROSPACE_DARK.surface  else AEROSPACE_LIGHT.surface
            "silicon", "data_science" -> if (dark) SILICON_DARK.surface    else SILICON_LIGHT.surface
            "glamer"                  -> if (dark) GLAMER_DARK.surface     else GLAMER_LIGHT.surface
            "blackwhite"              -> if (dark) BLACKWHITE_DARK.surface else BLACKWHITE_LIGHT.surface
            else                      -> if (dark) Color(0xFF1C1B1F)       else Color(0xFFFFFFFF)
        }
    }

    fun colorProvidersFor(
        theme: String,
        variant: String = "dark",
        customFont: String = "#1B1F27",
        customBackground: String = "#FFFFFF",
    ): ColorProviders {
        if (theme == "custom") {
            val scheme = customColorScheme(variant, customFont, customBackground)
            return buildColorProviders(light = scheme, dark = scheme)
        }
        val dark = variant == "dark"
        return when (theme) {
            "lavender", "light"       -> if (dark) buildColorProviders(light = LAVENDER_DARK,    dark = LAVENDER_DARK)
                                         else      buildColorProviders(light = LAVENDER_LIGHT,   dark = LAVENDER_LIGHT)
            "amethyst", "dark"        -> if (dark) buildColorProviders(light = AMETHYST_DARK,    dark = AMETHYST_DARK)
                                         else      buildColorProviders(light = AMETHYST_LIGHT,   dark = AMETHYST_LIGHT)
            "glassy"                  -> if (dark) buildColorProviders(light = GLASSY_DARK,      dark = GLASSY_DARK)
                                         else      buildColorProviders(light = GLASSY_LIGHT,     dark = GLASSY_LIGHT)
            "simple"                  -> if (dark) buildColorProviders(light = SIMPLE_DARK,      dark = SIMPLE_DARK)
                                         else      buildColorProviders(light = SIMPLE_LIGHT,     dark = SIMPLE_LIGHT)
            "aerospace"               -> if (dark) buildColorProviders(light = AEROSPACE_DARK,   dark = AEROSPACE_DARK)
                                         else      buildColorProviders(light = AEROSPACE_LIGHT,  dark = AEROSPACE_LIGHT)
            "silicon", "data_science" -> if (dark) buildColorProviders(light = SILICON_DARK,     dark = SILICON_DARK)
                                         else      buildColorProviders(light = SILICON_LIGHT,    dark = SILICON_LIGHT)
            "glamer"                  -> if (dark) buildColorProviders(light = GLAMER_DARK,      dark = GLAMER_DARK)
                                         else      buildColorProviders(light = GLAMER_LIGHT,     dark = GLAMER_LIGHT)
            "blackwhite"              -> if (dark) buildColorProviders(light = BLACKWHITE_DARK,  dark = BLACKWHITE_DARK)
                                         else      buildColorProviders(light = BLACKWHITE_LIGHT, dark = BLACKWHITE_LIGHT)
            else                      -> buildColorProviders(light = lightColorScheme(), dark = darkColorScheme()) // "auto"
        }
    }

    // Android-framework-free hex parser - android.graphics.Color.parseColor() throws
    // "not mocked" in this project's plain JUnit tests (no Robolectric), so this hand-written
    // version keeps the "custom" theme's color logic unit-testable. Accepts "#RRGGBB" or
    // "#AARRGGBB" (leading '#' optional); returns null for anything else rather than
    // throwing, same tolerant contract android.graphics.Color.parseColor() would have had.
    internal fun parseHexColor(hex: String): Color? {
        val cleaned = hex.trim().removePrefix("#")
        if (cleaned.length != 6 && cleaned.length != 8) return null
        return runCatching {
            val argb = if (cleaned.length == 6) "FF$cleaned" else cleaned
            Color(argb.toLong(16).toInt())
        }.getOrNull()
    }

    // Builds a full ColorScheme from just two user-picked colors. Dark variant swaps them
    // (background becomes the font color, font becomes the background) rather than asking
    // for a second pair of colors - see the design doc's "invert" decision. Secondary colors
    // (muted text, dividers) are derived by opacity rather than separately configurable.
    private fun customColorScheme(variant: String, fontHex: String, backgroundHex: String): ColorScheme {
        val font = parseHexColor(fontHex) ?: Color(0xFF1B1F27)
        val background = parseHexColor(backgroundHex) ?: Color(0xFFFFFFFF)
        val resolvedBackground = if (variant == "dark") font else background
        val resolvedText       = if (variant == "dark") background else font
        val scheme = if (variant == "dark") darkColorScheme() else lightColorScheme()
        return scheme.copy(
            background       = resolvedBackground,
            surface          = resolvedBackground,
            onBackground     = resolvedText,
            onSurface        = resolvedText,
            onSurfaceVariant = resolvedText.copy(alpha = 0.6f),
            outline          = resolvedText.copy(alpha = 0.25f),
        )
    }
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.glance.WidgetThemesTest"`
Expected: PASS (9 tests, 0 failures) — verify by hand-tracing per Step 2's note.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/WidgetThemes.kt app/src/test/java/com/newsfeed/widget/glance/WidgetThemesTest.kt
git commit -m "Add WidgetThemes.parseHexColor() and a custom theme branch, with unit tests"
```

---

### Task 3: Wire "Custom" into the Settings screen

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt:557` (the
  `rawColorSchemeFor` call site)
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt:695-718` (the
  theme picker)

No new unit tests here — this is a Compose UI change verified on-device in Task 5, matching
how this settings screen has always been verified.

- [ ] **Step 1: Add "Custom" to the theme picker**

In `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt`, find:

```kotlin
                                val themeOptions = listOf(
                                    "auto"       to "Auto (system)",
                                    "lavender"   to "Lavender",
                                    "amethyst"   to "Amethyst",
                                    "glassy"     to "Glassy",
                                    "simple"     to "Simple",
                                    "aerospace"  to "Aerospace",
                                    "silicon"    to "Data Science",
                                    "glamer"     to "Glamour",
                                    "blackwhite" to "Black & White",
                                )
```

Replace with:

```kotlin
                                val themeOptions = listOf(
                                    "auto"       to "Auto (system)",
                                    "lavender"   to "Lavender",
                                    "amethyst"   to "Amethyst",
                                    "glassy"     to "Glassy",
                                    "simple"     to "Simple",
                                    "aerospace"  to "Aerospace",
                                    "silicon"    to "Data Science",
                                    "glamer"     to "Glamour",
                                    "blackwhite" to "Black & White",
                                    "custom"     to "Custom",
                                )
```

- [ ] **Step 2: Pass the custom colors through the preview call site**

In the same file, find (around line 557):

```kotlin
                                val previewScheme = WidgetThemes.rawColorSchemeFor(config.widgetTheme, config.themeVariant)
```

Replace with:

```kotlin
                                val previewScheme = WidgetThemes.rawColorSchemeFor(
                                    config.widgetTheme, config.themeVariant,
                                    config.customFontColor, config.customBackgroundColor,
                                )
```

- [ ] **Step 3: Add the two hex color inputs, shown only for Custom theme**

In the same file, find the end of the existing "Theme variant" row (directly after the theme
picker block from Step 1, and directly before the "Use theme accent colors" row):

```kotlin
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
```

Add directly after this block's closing `}` (still before the "Use theme accent colors"
row):

```kotlin
                                if (config.widgetTheme == "custom") {
                                    listOf(
                                        Triple("Font color", config.customFontColor) { v: String -> config = config.copy(customFontColor = v) },
                                        Triple("Background color", config.customBackgroundColor) { v: String -> config = config.copy(customBackgroundColor = v) },
                                    ).forEach { (label, value, onChange) ->
                                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                            Text(label, style = MaterialTheme.typography.bodyMedium)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val parsed = WidgetThemes.parseHexColor(value)
                                                Box(
                                                    Modifier.size(20.dp)
                                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                                        .background(parsed ?: androidx.compose.ui.graphics.Color.Gray)
                                                        .border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                OutlinedTextField(
                                                    value = value,
                                                    onValueChange = onChange,
                                                    singleLine = true,
                                                    modifier = Modifier.width(110.dp),
                                                    textStyle = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                }
```

`androidx.compose.foundation.layout.Box` and `androidx.compose.foundation.border` are not
yet imported in this file — add these two imports alongside the existing
`androidx.compose.foundation.layout.*` imports near the top of the file:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
```

`androidx.compose.foundation.shape.CircleShape` and `androidx.compose.ui.graphics.Color` are
used fully-qualified above rather than imported, matching this file's own existing
convention (e.g. `androidx.compose.ui.graphics.Color.Transparent` a few lines above in the
"Theme variant" row you just read) — do not add unqualified imports for these that could
shadow or conflict with the file's existing fully-qualified usages.

- [ ] **Step 4: Verify the project compiles**

Local Gradle is not runnable in this sandbox. Hand-verify: re-read both edited regions for
brace balance and confirm the two new imports were added without disturbing any existing
import. Report `DONE_WITH_CONCERNS` for the inability to compile.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt
git commit -m "Add Custom theme option and font/background color inputs to Settings"
```

---

### Task 4: Wire the custom colors into the real widget render

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt:266-267`

No new unit tests — this is the real-widget render path, verified on-device in Task 5.

- [ ] **Step 1: Update the two call sites**

In `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`, find:

```kotlin
    val themeColors = WidgetThemes.colorProvidersFor(config.widgetTheme, config.themeVariant)
    val surfaceColor = WidgetThemes.surfaceColorFor(config.widgetTheme, config.themeVariant)
```

Replace with:

```kotlin
    val themeColors = WidgetThemes.colorProvidersFor(
        config.widgetTheme, config.themeVariant, config.customFontColor, config.customBackgroundColor)
    val surfaceColor = WidgetThemes.surfaceColorFor(
        config.widgetTheme, config.themeVariant, config.customFontColor, config.customBackgroundColor)
```

- [ ] **Step 2: Verify the project compiles**

Local Gradle is not runnable in this sandbox. Hand-verify the two-line change's syntax and
confirm nothing else in the surrounding function was touched. Report `DONE_WITH_CONCERNS`
for the inability to compile.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt
git commit -m "Route the real widget's rendering through the custom theme colors"
```

---

### Task 5: On-device verification, `BUGS.md` update, and final push

**Files:** `docs/BUGS.md` (feature entry only)

- [ ] **Step 1: Push and install the CI-built release**

```bash
git push origin main
```

Wait for the `Build APK` GitHub Actions workflow to finish, download and install
`NewsFeed-latest.apk`, confirming `version.json`'s `versionCode` matches the just-pushed
commit before testing.

- [ ] **Step 2: Confirm Custom theme selection and color inputs**

Open Settings, select "Custom" from the theme picker, confirm the two new "Font color" /
"Background color" hex inputs appear directly below the Light/Dark toggle (and that no other
theme shows them).

- [ ] **Step 3: Confirm the colors apply and light/dark actually swap**

Type a distinctive font color (e.g. `#D4001A`) and background color (e.g. `#FFF3CD`), Save,
confirm the real widget's headline/body text and background reflect exactly those two colors
in Light variant. Switch "Theme variant" to Dark, Save, confirm the widget now shows the
background in `#D4001A` and the text in `#FFF3CD` — the swap, not a third color.

- [ ] **Step 4: Confirm no regression to the other 9 themes**

Switch through at least 2 other themes (e.g. Glamour, Simple) and confirm they render exactly
as before this change — the `if (theme == "custom")` early-return in each resolver function
should make this a pure addition with zero effect on existing themes.

- [ ] **Step 5: Update `docs/BUGS.md`**

Add a short "Feature additions" entry (matching the existing pattern) describing the new
Custom theme: whole-widget font/background color pair, automatic light/dark invert, derived
secondary colors — reference `docs/superpowers/specs/2026-09-10-custom-theme-colors-design.md`.

- [ ] **Step 6: Final push**

```bash
git add docs/BUGS.md
git commit -m "Document the custom font/background color theme feature"
git push origin main
```

Per this project's established workflow: run a security-focused review of the full diff
before this push (per the standing pre-push security check) — this feature has no network
calls, no new permissions, and no credential of any kind; confirm that remains true and that
`parseHexColor`'s tolerant null-on-failure behavior can't be driven into a crash by any input
a user could type into the two new text fields.

---

## Self-Review Notes

- **Spec coverage:** every section of the approved spec has a corresponding task — the two
  new `WidgetConfig` fields (Task 1), `WidgetThemes.kt`'s three resolver functions plus
  `customColorScheme()`/`parseHexColor()` (Task 2), the Settings UI including the theme
  picker addition and the two hex inputs (Task 3), the real-widget call sites in
  `NewsFeedWidget.kt` (Task 4), and on-device verification (Task 5). The spec's "Out of
  scope" items (per-feed colors, an HSV picker, separate light/dark pairs, forcing Black &
  White's favicon gap onto Custom theme) have no corresponding task, correctly.
- **Type/name consistency checked:** `parseHexColor`, `customColorScheme`,
  `customFontColor`, `customBackgroundColor` are named and used identically across Tasks
  1-4 wherever referenced.
- **No placeholders:** every step contains complete, real code.
