package com.readyou.widget.glance

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as buildColorProviders

object WidgetThemes {

    fun colorProvidersFor(theme: String): ColorProviders = when (theme) {
        "light"  -> buildColorProviders(light = LIGHT,  dark = LIGHT)
        "dark"   -> buildColorProviders(light = DARK,   dark = DARK)
        "glassy" -> buildColorProviders(light = GLASSY, dark = GLASSY)
        "simple" -> buildColorProviders(light = SIMPLE, dark = SIMPLE)
        "tech"   -> buildColorProviders(light = TECH,   dark = TECH)
        "glamer" -> buildColorProviders(light = GLAMER, dark = GLAMER)
        else     -> buildColorProviders(light = lightColorScheme(), dark = darkColorScheme())  // "auto"
    }

    // ── Light ─────────────────────────────────────────────────────────────────
    private val LIGHT = lightColorScheme(
        primary           = Color(0xFF9B72E3),
        onPrimary         = Color(0xFFFFFFFF),
        surface           = Color(0xFFFFFFFF),
        onSurface         = Color(0xFF1C1B1F),
        onSurfaceVariant  = Color(0xFF49454F),
        surfaceVariant    = Color(0xFFE0D8F0),
        background        = Color(0xFFF6F0FB),
        onBackground      = Color(0xFF1C1B1F),
    )

    // ── Dark ──────────────────────────────────────────────────────────────────
    private val DARK = darkColorScheme(
        primary           = Color(0xFFB39DDB),
        onPrimary         = Color(0xFF1C1B1F),
        surface           = Color(0xFF1C1B1F),
        onSurface         = Color(0xFFE6E1E5),
        onSurfaceVariant  = Color(0xFFCAC4D0),
        surfaceVariant    = Color(0xFF2D2B33),
        background        = Color(0xFF141218),
        onBackground      = Color(0xFFE6E1E5),
    )

    // ── Glassy (frosted-glass dark) ───────────────────────────────────────────
    // Alpha on surface/background lets the wallpaper bleed through.
    private val GLASSY = darkColorScheme(
        primary           = Color(0xFFB388FF),
        onPrimary         = Color(0xFF000000),
        surface           = Color(0xBB0A0A1A),   // ~73 % opaque dark blue-black
        onSurface         = Color(0xFFFFFFFF),
        onSurfaceVariant  = Color(0xB3E0D8FF),   // 70 % lavender-white
        surfaceVariant    = Color(0x2AFFFFFF),   // 16 % white
        background        = Color(0xBB0A0A1A),
        onBackground      = Color(0xFFFFFFFF),
    )

    // ── Simple (clean black-and-white editorial) ───────────────────────────────
    private val SIMPLE = lightColorScheme(
        primary           = Color(0xFF000000),
        onPrimary         = Color(0xFFFFFFFF),
        surface           = Color(0xFFFFFFFF),
        onSurface         = Color(0xFF000000),
        onSurfaceVariant  = Color(0xFF555555),
        surfaceVariant    = Color(0xFFF0F0F0),
        background        = Color(0xFFFFFFFF),
        onBackground      = Color(0xFF000000),
    )

    // ── Tech (terminal / hacker) ───────────────────────────────────────────────
    private val TECH = darkColorScheme(
        primary           = Color(0xFF39D353),
        onPrimary         = Color(0xFF0D1117),
        surface           = Color(0xFF0D1117),
        onSurface         = Color(0xFF39D353),
        onSurfaceVariant  = Color(0xFF8B949E),
        surfaceVariant    = Color(0xFF21262D),
        background        = Color(0xFF0D1117),
        onBackground      = Color(0xFF39D353),
    )

    // ── Glamour (velvet purple + gold) ─────────────────────────────────────────
    private val GLAMER = darkColorScheme(
        primary           = Color(0xFFFFD700),
        onPrimary         = Color(0xFF1A0033),
        surface           = Color(0xFF1A0033),
        onSurface         = Color(0xFFFFD700),
        onSurfaceVariant  = Color(0xFFE040FB),
        surfaceVariant    = Color(0xFF4A0080),
        background        = Color(0xFF1A0033),
        onBackground      = Color(0xFFFFD700),
    )
}
