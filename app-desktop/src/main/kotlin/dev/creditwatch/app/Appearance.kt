package dev.creditwatch.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import java.util.prefs.Preferences

enum class ThemeMode(val label: String) {
    SYSTEM("System"), DARK("Dark"), LIGHT("Light"),
}

data class CreditWatchPalette(
    val background: Color,
    val panel: Color,
    val raised: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val healthy: Color,
    val warning: Color,
)

val darkPalette = CreditWatchPalette(
    background = Color(0xFF0B1110),
    panel = Color(0xFF17201D),
    raised = Color(0xFF23372F),
    text = Color(0xFFF1F5F2),
    muted = Color(0xFFA8B8AF),
    accent = Color(0xFF69D9C3),
    healthy = Color(0xFF86D79B),
    warning = Color(0xFFFFC77E),
)

val lightPalette = CreditWatchPalette(
    background = Color(0xFFF7F9F7),
    panel = Color(0xFFFFFFFF),
    raised = Color(0xFFE4F4ED),
    text = Color(0xFF17201D),
    muted = Color(0xFF52635C),
    accent = Color(0xFF087F70),
    healthy = Color(0xFF207245),
    warning = Color(0xFF925408),
)

val LocalCreditWatchPalette = compositionLocalOf { darkPalette }

object AppearanceSettings {
    private const val KEY = "themeMode"
    private val preferences by lazy { Preferences.userNodeForPackage(AppearanceSettings::class.java) }

    fun load(): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences.get(KEY, ThemeMode.SYSTEM.name))
    }.getOrDefault(ThemeMode.SYSTEM)

    fun save(mode: ThemeMode) {
        runCatching { preferences.put(KEY, mode.name) }
    }
}

val palette: CreditWatchPalette
    @Composable get() = LocalCreditWatchPalette.current
