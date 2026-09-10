# Custom font color and background color theme — design

## Context

The widget currently offers 9 preset themes (`auto`, `lavender`, `amethyst`, `glassy`,
`simple`, `aerospace`, `silicon`, `glamer`, `blackwhite`), each a hardcoded light+dark color
pair in `WidgetThemes.kt`. There is no way for a user to pick their own colors — only choose
among presets.

## Decisions made during brainstorming

- **Scope: a new 10th theme option, "Custom," not an overlay on existing themes.** Matches
  how theme selection already works today — one clear choice, no interaction with the other
  9 themes' fixed palettes.
- **Level: whole-widget, not per-feed.** One font color + one background color for the
  entire widget, selected as part of picking "Custom" as the theme — matches how every other
  theme already works. (Per-feed accent color, RTL, and font-family settings are unrelated
  and untouched by this feature.)
- **Only 2 colors are ever picked.** Secondary/derived colors (muted text, dividers) are
  computed automatically from the two picks rather than exposed as more settings:
  - Muted/secondary text (`onSurfaceVariant`): font color at 60% opacity.
  - Dividers/outline: font color at 25% opacity.
- **Light/dark variant: reuses the existing "Theme variant" Light/Dark toggle
  (`config.themeVariant`), already present in the Settings UI right below the theme picker
  — no new UI needed for this.** Light variant = your two colors exactly as picked
  (background = your background color, text = your font color). Dark variant = the two
  colors swapped (background = your font color, text = your background color) — an
  automatic invert, not a second pair of colors to pick.
- **Input mechanism: hex text fields, not the existing swatch-grid picker.** The per-feed
  accent-color picker (`ColorPickerGrid.kt`) is a small curated palette (12 preset swatches
  per theme) — the opposite of "custom." A plain hex `OutlinedTextField` (e.g. `#3A2E14`)
  with a live color-swatch preview next to it needs no new dependency and matches this
  screen's existing plain-Material3-input style (e.g. the Add Feed URL field).

## Architecture

Two new `WidgetConfig` fields carry the user's picks. `WidgetThemes.kt`'s three existing
theme-resolution functions — `rawColorSchemeFor()`, `surfaceColorFor()`, `colorProvidersFor()`
— are extended to accept these two hex values (defaulted so every existing call site keeps
compiling unchanged for the other 9 themes) and, when `theme == "custom"`, build a
`ColorScheme` from them directly instead of selecting a hardcoded palette. There are only 3
call sites total (`WidgetConfigActivity.kt:557`, `NewsFeedWidget.kt:266-267`), and all three
already have the full `WidgetConfig` object in scope, so passing the two extra values through
is a small, contained change. **No changes are needed anywhere in `FeedItemRow.kt`** — its
non-Glamour headline/body text already reads `GlanceTheme.colors.onSurface`, which flows
from whatever `colorProvidersFor()` returns; Custom theme uses that same non-Glamour
plain-text rendering path already used by every other theme except Glamour (confirmed:
`fontFamilyFor()`'s `when` has no `"custom"` branch, so it falls to the `else -> "sans"` case
— exactly like most existing themes).

## Components

### `data/FeedConfig.kt` — two new `WidgetConfig` fields

```kotlin
val customFontColor: String = "#1B1F27",       // used only when widgetTheme == "custom"
val customBackgroundColor: String = "#FFFFFF", // dark-on-light default so it looks
                                                // reasonable before the user changes anything
```

And `"custom"` added to the `widgetTheme` field's existing comment listing valid values.

### `glance/WidgetThemes.kt` — extend the three resolver functions

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
        // ...existing branches unchanged...
        else -> if (dark) darkColorScheme() else lightColorScheme()
    }
}

fun surfaceColorFor(
    theme: String,
    variant: String = "dark",
    customFont: String = "#1B1F27",
    customBackground: String = "#FFFFFF",
): Color {
    if (theme == "custom") return customColorScheme(variant, customFont, customBackground).surface
    // ...existing body unchanged...
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
    // ...existing body unchanged...
}

// Parses the two hex strings (falling back to the field defaults on invalid input, same
// runCatching-based tolerance already used for per-feed accentColor parsing elsewhere in
// this project) and builds a full ColorScheme from just those two colors, swapping them for
// the dark variant per the "invert" decision above. onSurfaceVariant/outline are derived by
// alpha-blending the font color, not separately configurable.
private fun customColorScheme(variant: String, fontHex: String, backgroundHex: String): ColorScheme {
    val font = runCatching { Color(android.graphics.Color.parseColor(fontHex)) }
        .getOrDefault(Color(0xFF1B1F27))
    val background = runCatching { Color(android.graphics.Color.parseColor(backgroundHex)) }
        .getOrDefault(Color(0xFFFFFFFF))
    // Dark variant swaps the two colors (the "invert" decision above) - no separate
    // dark-mode colors are ever picked.
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

### `config/WidgetConfigActivity.kt` — theme picker + new color inputs

Add to the existing `themeOptions` list (`WidgetConfigActivity.kt:695-705`):

```kotlin
"custom" to "Custom",
```

Update the 3 call sites to pass the config's custom colors through:

```kotlin
// WidgetConfigActivity.kt:557
val previewScheme = WidgetThemes.rawColorSchemeFor(
    config.widgetTheme, config.themeVariant, config.customFontColor, config.customBackgroundColor)

// NewsFeedWidget.kt:266-267
val themeColors = WidgetThemes.colorProvidersFor(
    config.widgetTheme, config.themeVariant, config.customFontColor, config.customBackgroundColor)
val surfaceColor = WidgetThemes.surfaceColorFor(
    config.widgetTheme, config.themeVariant, config.customFontColor, config.customBackgroundColor)
```

Immediately below the existing "Theme variant" Light/Dark row (`WidgetConfigActivity.kt`,
right after line 739's closing brace), add two hex inputs shown only for Custom theme:

```kotlin
if (config.widgetTheme == "custom") {
    listOf(
        Triple("Font color", config.customFontColor) { v: String -> config = config.copy(customFontColor = v) },
        Triple("Background color", config.customBackgroundColor) { v: String -> config = config.copy(customBackgroundColor = v) },
    ).forEach { (label, value, onChange) ->
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parsed = runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(value)) }
                    .getOrNull()
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(parsed ?: androidx.compose.ui.graphics.Color.Gray)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
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

Invalid hex input (mid-typing, or a typo) simply fails `parseColor` and the preview swatch
falls back to gray — the same tolerant pattern `ColorPickerGrid.kt` already uses for
`accentColor`. There is no separate "confirm"/validation step; whatever is currently
resolvable is used live, matching how every other setting on this screen already behaves.

## Error handling

- Invalid or partially-typed hex strings never crash — `customColorScheme()`'s
  `runCatching` falls back to the field's own default color, and the config-screen swatch
  preview falls back to gray. The user can keep typing without the screen breaking.
- No new persisted state beyond the two new plain `String` fields — `WidgetConfig` is
  already a `@Serializable`-compatible data class persisted via existing DataStore
  machinery; two more `String` fields need no migration beyond their own defaults applying
  to configs that predate this feature (a pre-existing widget simply never had `"custom"` as
  its `widgetTheme`, so the new fields are inert until the user actually selects it).

## Out of scope

- Per-feed custom colors (explicitly rejected during brainstorming — whole-widget only).
- A full color-wheel/HSV picker or any new picker dependency — plain hex input only.
- Separate light/dark color pairs (4 colors) — explicitly rejected in favor of the automatic
  invert.
- Forcing the Black & White theme's known favicon/banner monochrome gap (a separate,
  already-tracked, still-open issue in `BUGS.md`) to also apply to Custom theme — out of
  scope for this feature; Custom theme inherits the same pre-existing behavior every other
  theme has for favicons/banners.

## Testing / verification

No new pure-logic unit test candidate beyond `customColorScheme()`'s hex-parsing and
swap-on-dark-variant behavior, which is straightforward enough to verify by hand-tracing
(this project's `TelegramFeedParserTest.kt` JUnit infrastructure could house a test for it,
but the function is small and this is not required). On-device verification: select
"Custom" theme, confirm the two color inputs appear, type a distinctive font color and
background color, confirm the live config-screen preview reflects them, Save, confirm the
real widget matches, toggle "Theme variant" to Dark, confirm the widget now shows the two
colors swapped (not a third, different color), confirm every other theme's appearance and
behavior is completely unchanged (the `if (theme == "custom")` early-return in each resolver
function should make this a pure addition with zero risk to existing themes).
