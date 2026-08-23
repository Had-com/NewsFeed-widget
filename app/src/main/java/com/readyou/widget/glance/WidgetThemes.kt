package com.readyou.widget.glance

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as buildColorProviders

object WidgetThemes {

    fun colorProvidersFor(theme: String): ColorProviders = when (theme) {
        "lavender", "light"        -> buildColorProviders(light = LAVENDER,     dark = LAVENDER)
        "amethyst", "dark"         -> buildColorProviders(light = AMETHYST,     dark = AMETHYST)
        "glassy"                   -> buildColorProviders(light = GLASSY,       dark = GLASSY)
        "simple"                   -> buildColorProviders(light = SIMPLE,       dark = SIMPLE)
        "aerospace"                -> buildColorProviders(light = AEROSPACE,    dark = AEROSPACE)
        "silicon", "data_science"  -> buildColorProviders(light = SILICON,      dark = SILICON)
        "glamer"                   -> buildColorProviders(light = GLAMER,       dark = GLAMER)
        else                       -> buildColorProviders(light = lightColorScheme(), dark = darkColorScheme())  // "auto"
    }

    // ── Lavender ──────────────────────────────────────────────────────────────
    private val LAVENDER = lightColorScheme(
        primary           = Color(0xFF9B72E3),
        onPrimary         = Color(0xFFFFFFFF),
        surface           = Color(0xFFFFFFFF),
        onSurface         = Color(0xFF1C1B1F),
        onSurfaceVariant  = Color(0xFF49454F),
        surfaceVariant    = Color(0xFFE0D8F0),
        background        = Color(0xFFF6F0FB),
        onBackground      = Color(0xFF1C1B1F),
    )

    // ── Amethyst ──────────────────────────────────────────────────────────────
    private val AMETHYST = darkColorScheme(
        primary           = Color(0xFFB39DDB),
        onPrimary         = Color(0xFF1C1B1F),
        surface           = Color(0xFF1C1B1F),
        onSurface         = Color(0xFFE6E1E5),
        onSurfaceVariant  = Color(0xFFCAC4D0),
        surfaceVariant    = Color(0xFF2D2B33),
        background        = Color(0xFF141218),
        onBackground      = Color(0xFFE6E1E5),
    )

    // ── Glassy (frosted-glass, semi-transparent) ──────────────────────────────
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

    // ── Aerospace (mission control — amber on near-black charcoal) ─────────────
    private val AEROSPACE = darkColorScheme(
        primary           = Color(0xFFF5A623),
        onPrimary         = Color(0xFF0A0804),
        surface           = Color(0xFF111418),
        onSurface         = Color(0xFFD0D8E0),
        onSurfaceVariant  = Color(0xFF5A6070),
        surfaceVariant    = Color(0xFF1C1E22),
        background        = Color(0xFF0A0B0D),
        onBackground      = Color(0xFFD0D8E0),
    )

    // ── Silicon / Data Science (teal-mint on deep navy) ───────────────────────
    private val SILICON = darkColorScheme(
        primary           = Color(0xFF00C4B4),
        onPrimary         = Color(0xFF001814),
        surface           = Color(0xFF141A20),
        onSurface         = Color(0xFFC0D0DC),
        onSurfaceVariant  = Color(0xFF4A7080),
        surfaceVariant    = Color(0xFF1C2830),
        background        = Color(0xFF0C1014),
        onBackground      = Color(0xFFC0D0DC),
    )

    // ── Glamour (beige / warm cream) ───────────────────────────────────────────
    private val GLAMER = darkColorScheme(
        primary           = Color(0xFFD4A574),
        onPrimary         = Color(0xFF1E1410),
        surface           = Color(0xFF26190F),
        onSurface         = Color(0xFFF2E8DC),
        onSurfaceVariant  = Color(0xFFA08060),
        surfaceVariant    = Color(0xFF3A2818),
        background        = Color(0xFF1A0F08),
        onBackground      = Color(0xFFF2E8DC),
    )
}
