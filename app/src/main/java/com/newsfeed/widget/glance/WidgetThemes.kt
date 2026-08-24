package com.newsfeed.widget.glance

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProviders
import androidx.glance.material3.ColorProviders as buildColorProviders

object WidgetThemes {

    // Returns "serif", "mono", or "sans" — used as the theme's default typeface.
    // Maps to system FontFamily in Glance (RemoteViews only supports system fonts).
    // Android uses these exact fonts: SansSerif=Roboto, Monospace=Roboto Mono, Serif=Noto Serif,
    // Cursive=Dancing Script. Hebrew falls back within the same family automatically:
    // Serif→Noto Serif Hebrew, SansSerif/Mono/Cursive→Noto Sans Hebrew / Rubik.
    fun fontFamilyFor(theme: String): String = when (theme) {
        "glamer"                  -> "cursive"   // Dancing Script / Hebrew: Noto Sans Hebrew
        "lavender", "light"       -> "serif"     // Lora / Hebrew: Noto Serif Hebrew
        "amethyst", "dark"        -> "serif"     // Lora / Hebrew: Noto Serif Hebrew
        "aerospace"               -> "mono"      // Roboto Mono (exact) / Hebrew: Noto Sans Hebrew
        "silicon", "data_science" -> "mono"      // Roboto Mono (exact) / Hebrew: Noto Sans Hebrew
        else                      -> "sans"      // Roboto (exact) / Hebrew: Roboto Hebrew
    }

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
            else                      -> buildColorProviders(light = lightColorScheme(), dark = darkColorScheme()) // "auto"
        }
    }

    // ── Lavender ──────────────────────────────────────────────────────────────
    private val LAVENDER_LIGHT = lightColorScheme(
        primary             = Color(0xFF9B72E3),
        onPrimary           = Color(0xFFFFFFFF),
        primaryContainer    = Color(0xFFEDE1FF),
        onPrimaryContainer  = Color(0xFF22005D),
        surface             = Color(0xFFFFFFFF),
        onSurface           = Color(0xFF1C1B1F),
        onSurfaceVariant    = Color(0xFF49454F),
        surfaceVariant      = Color(0xFFE0D8F0),
        background          = Color(0xFFF6F0FB),
        onBackground        = Color(0xFF1C1B1F),
    )
    private val LAVENDER_DARK = darkColorScheme(
        primary             = Color(0xFFB08AFF),
        onPrimary           = Color(0xFF1A0050),
        primaryContainer    = Color(0xFF3E1F98),
        onPrimaryContainer  = Color(0xFFE8E0F8),
        surface             = Color(0xFF1A1628),
        onSurface           = Color(0xFFE8E0F8),
        onSurfaceVariant    = Color(0xFF9A92B8),
        surfaceVariant      = Color(0xFF2E2840),
        background          = Color(0xFF120E20),
        onBackground        = Color(0xFFE8E0F8),
    )

    // ── Amethyst ──────────────────────────────────────────────────────────────
    private val AMETHYST_LIGHT = lightColorScheme(
        primary             = Color(0xFF7B5CB8),
        onPrimary           = Color(0xFFFFFFFF),
        primaryContainer    = Color(0xFFDDD0F5),
        onPrimaryContainer  = Color(0xFF1C0F40),
        surface             = Color(0xFFF2EDFC),
        onSurface           = Color(0xFF1C1B1F),
        onSurfaceVariant    = Color(0xFF5A4878),
        surfaceVariant      = Color(0xFFD8CCEE),
        background          = Color(0xFFEAE2F8),
        onBackground        = Color(0xFF1C1B1F),
    )
    private val AMETHYST_DARK = darkColorScheme(
        primary             = Color(0xFFB39DDB),
        onPrimary           = Color(0xFF1C1B1F),
        primaryContainer    = Color(0xFF4A3575),
        onPrimaryContainer  = Color(0xFFE6E1E5),
        surface             = Color(0xFF1C1B1F),
        onSurface           = Color(0xFFE6E1E5),
        onSurfaceVariant    = Color(0xFFCAC4D0),
        surfaceVariant      = Color(0xFF2D2B33),
        background          = Color(0xFF141218),
        onBackground        = Color(0xFFE6E1E5),
    )

    // ── Glassy ────────────────────────────────────────────────────────────────
    // Alpha values let the wallpaper bleed through.
    private val GLASSY_LIGHT = lightColorScheme(
        primary             = Color(0xFF7C4DFF),
        onPrimary           = Color(0xFFFFFFFF),
        primaryContainer    = Color(0xFFE8DDFF),
        onPrimaryContainer  = Color(0xFF1A1A2E),
        surface             = Color(0x8CFFFFFF),   // 55 % white
        onSurface           = Color(0xFF1A1A2E),
        onSurfaceVariant    = Color(0xFF4A4A7A),
        surfaceVariant      = Color(0x14001E00),   // 8 % dark tint
        background          = Color(0x99F0F5FF),   // 60 % pale blue
        onBackground        = Color(0xFF1A1A2E),
    )
    private val GLASSY_DARK = darkColorScheme(
        primary             = Color(0xFFB388FF),
        onPrimary           = Color(0xFF000000),
        primaryContainer    = Color(0xFF3D1A9E),
        onPrimaryContainer  = Color(0xFFFFFFFF),
        surface             = Color(0xBB0A0A1A),   // 73 % dark blue-black
        onSurface           = Color(0xFFFFFFFF),
        onSurfaceVariant    = Color(0xB3E0D8FF),   // 70 % lavender-white
        surfaceVariant      = Color(0x2AFFFFFF),   // 16 % white
        background          = Color(0xBB0A0A1A),
        onBackground        = Color(0xFFFFFFFF),
    )

    // ── Simple ────────────────────────────────────────────────────────────────
    private val SIMPLE_LIGHT = lightColorScheme(
        primary             = Color(0xFF000000),
        onPrimary           = Color(0xFFFFFFFF),
        primaryContainer    = Color(0xFFE0E0E0),
        onPrimaryContainer  = Color(0xFF000000),
        surface             = Color(0xFFFFFFFF),
        onSurface           = Color(0xFF000000),
        onSurfaceVariant    = Color(0xFF555555),
        surfaceVariant      = Color(0xFFF0F0F0),
        background          = Color(0xFFFFFFFF),
        onBackground        = Color(0xFF000000),
    )
    private val SIMPLE_DARK = darkColorScheme(
        primary             = Color(0xFFFFFFFF),
        onPrimary           = Color(0xFF111111),
        primaryContainer    = Color(0xFF2C2C2C),
        onPrimaryContainer  = Color(0xFFFFFFFF),
        surface             = Color(0xFF111111),
        onSurface           = Color(0xFFFFFFFF),
        onSurfaceVariant    = Color(0xFF909090),
        surfaceVariant      = Color(0xFF1F1F1F),
        background          = Color(0xFF0A0A0A),
        onBackground        = Color(0xFFFFFFFF),
    )

    // ── Aerospace ─────────────────────────────────────────────────────────────
    private val AEROSPACE_LIGHT = lightColorScheme(
        primary             = Color(0xFFC07800),
        onPrimary           = Color(0xFFFFFFFF),
        primaryContainer    = Color(0xFFFFE5B4),
        onPrimaryContainer  = Color(0xFF1A1008),
        surface             = Color(0xFFFFFCF5),
        onSurface           = Color(0xFF1A1008),
        onSurfaceVariant    = Color(0xFF604518),
        surfaceVariant      = Color(0xFFE8D8A8),
        background          = Color(0xFFF5EDD8),
        onBackground        = Color(0xFF1A1008),
    )
    private val AEROSPACE_DARK = darkColorScheme(
        primary             = Color(0xFFF5A623),
        onPrimary           = Color(0xFF0A0804),
        primaryContainer    = Color(0xFF3D2800),
        onPrimaryContainer  = Color(0xFFD0D8E0),
        surface             = Color(0xFF111418),
        onSurface           = Color(0xFFD0D8E0),
        onSurfaceVariant    = Color(0xFF5A6070),
        surfaceVariant      = Color(0xFF1C1E22),
        background          = Color(0xFF0A0B0D),
        onBackground        = Color(0xFFD0D8E0),
    )

    // ── Silicon / Data Science ─────────────────────────────────────────────────
    private val SILICON_LIGHT = lightColorScheme(
        primary             = Color(0xFF007870),
        onPrimary           = Color(0xFFFFFFFF),
        primaryContainer    = Color(0xFFB2EBE8),
        onPrimaryContainer  = Color(0xFF061210),
        surface             = Color(0xFFF4FAFA),
        onSurface           = Color(0xFF061210),
        onSurfaceVariant    = Color(0xFF2A6060),
        surfaceVariant      = Color(0xFFC0E0DC),
        background          = Color(0xFFE4F4F2),
        onBackground        = Color(0xFF061210),
    )
    private val SILICON_DARK = darkColorScheme(
        primary             = Color(0xFF00C4B4),
        onPrimary           = Color(0xFF001814),
        primaryContainer    = Color(0xFF003530),
        onPrimaryContainer  = Color(0xFFC0D0DC),
        surface             = Color(0xFF141A20),
        onSurface           = Color(0xFFC0D0DC),
        onSurfaceVariant    = Color(0xFF4A7080),
        surfaceVariant      = Color(0xFF1C2830),
        background          = Color(0xFF0C1014),
        onBackground        = Color(0xFFC0D0DC),
    )

    // ── Glamour ───────────────────────────────────────────────────────────────
    private val GLAMER_LIGHT = lightColorScheme(
        primary             = Color(0xFFA87840),
        onPrimary           = Color(0xFFFFFFFF),
        primaryContainer    = Color(0xFFF3D5AD),
        onPrimaryContainer  = Color(0xFF2C1A0A),
        surface             = Color(0xFFFDFAF6),
        onSurface           = Color(0xFF2C1A0A),
        onSurfaceVariant    = Color(0xFF7A5C3A),
        surfaceVariant      = Color(0xFFEDE4D4),
        background          = Color(0xFFF5EFE6),
        onBackground        = Color(0xFF2C1A0A),
    )
    private val GLAMER_DARK = darkColorScheme(
        primary             = Color(0xFFD4A574),
        onPrimary           = Color(0xFF1E1410),
        primaryContainer    = Color(0xFF5C3820),
        onPrimaryContainer  = Color(0xFFF2E8DC),
        surface             = Color(0xFF26190F),
        onSurface           = Color(0xFFF2E8DC),
        onSurfaceVariant    = Color(0xFFA08060),
        surfaceVariant      = Color(0xFF3A2818),
        background          = Color(0xFF1A0F08),
        onBackground        = Color(0xFFF2E8DC),
    )
}
