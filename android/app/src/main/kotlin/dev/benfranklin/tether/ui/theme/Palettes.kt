package dev.benfranklin.tether.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * One theme's colours (0.5.5). The keys match the approved mockup kit (v055-home/kit.py PALETTES):
 * BG, SURF…SURF4, LINE…LINE3, TXT, ACC (the accent: play button, primary pills, progress, badges,
 * switches, selection; text on an ACC fill uses BG), T2…T4 (secondary, faint, fainter text).
 */
@Immutable
data class TetherPalette(
    val light: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val line: Color,
    val line2: Color,
    val line3: Color,
    val text: Color,
    val accent: Color,
    val t2: Color,
    val t3: Color,
    val t4: Color,
)

data class ThemeSpec(val id: String, val name: String, val palette: TetherPalette)

@Suppress("LongParameterList")
private fun pal(
    light: Boolean, bg: Long, s: Long, s2: Long, s3: Long, s4: Long, l: Long, l2: Long, l3: Long,
    txt: Long, acc: Long, t2: Long, t3: Long, t4: Long,
) = TetherPalette(
    light, c(bg), c(s), c(s2), c(s3), c(s4), c(l), c(l2), c(l3), c(txt), c(acc), c(t2), c(t3), c(t4),
)

private fun c(rgb: Long) = Color(0xFF000000 or rgb)

/** The 18 themes: 9 dark (Mono first, the default) and 9 light, with the exact mockup hex values. */
@Suppress("MagicNumber", "MaxLineLength")
object Themes {
    val MONO = ThemeSpec("mono", "Mono", pal(false, 0x09090A, 0x111112, 0x171718, 0x1F1F21, 0x2A2A2C, 0x1A1A1C, 0x222224, 0x333335, 0xF2F2F2, 0xE8E8E8, 0x9A9A9C, 0x626264, 0x48484A))
    val OLED = ThemeSpec("oled", "OLED Black", pal(false, 0x000000, 0x0A0A0A, 0x101010, 0x181818, 0x262626, 0x141414, 0x1C1C1C, 0x2C2C2C, 0xF2F2F2, 0xE8E8E8, 0x9A9A9C, 0x626264, 0x48484A))
    val MIDNIGHT = ThemeSpec("midnight", "Midnight", pal(false, 0x080A10, 0x0F121A, 0x151925, 0x1D2230, 0x2A3042, 0x171B26, 0x1F2433, 0x2E3447, 0xEEF1F8, 0xA9BEFF, 0x949BB0, 0x5E6478, 0x454A5A))
    val SAND = ThemeSpec("sand", "Sand", pal(false, 0x0C0B09, 0x13120F, 0x1A1814, 0x23201B, 0x302C25, 0x1C1A16, 0x25221D, 0x36322B, 0xF3EEE6, 0xE8D9BE, 0xA39C90, 0x6A645B, 0x4C4740))
    val VIOLET = ThemeSpec("violet", "Violet", pal(false, 0x0B0A10, 0x121119, 0x18171F, 0x211F2B, 0x2D2A3B, 0x1B1A23, 0x24222E, 0x35323F, 0xF1EFF8, 0xC7B8FF, 0x9C98AE, 0x645F75, 0x4A4658))
    val OCEAN = ThemeSpec("ocean", "Ocean", pal(false, 0x060B0F, 0x0C141B, 0x111B24, 0x172431, 0x223344, 0x131D27, 0x1A2733, 0x283A4A, 0xEDF4F8, 0x8FD3FF, 0x8EA3B2, 0x587080, 0x3F5260))
    val ROSE = ThemeSpec("rose", "Rose", pal(false, 0x0F0A0C, 0x161013, 0x1D1519, 0x281C22, 0x38272F, 0x1F161A, 0x291D23, 0x3B2A32, 0xF8EEF1, 0xF4B6C8, 0xB09AA2, 0x76626A, 0x54454B))
    val SLATE = ThemeSpec("slate", "Slate", pal(false, 0x0B0D0F, 0x121519, 0x181C21, 0x20252C, 0x2C333C, 0x181B20, 0x20242A, 0x30363F, 0xEEF0F2, 0xC3CCD8, 0x98A0AA, 0x626A74, 0x474D55))
    val MOCHA = ThemeSpec("mocha", "Mocha", pal(false, 0x0E0A08, 0x17110D, 0x1E1712, 0x291F18, 0x3A2C22, 0x1F1712, 0x2A1F18, 0x3D2E24, 0xF5EDE6, 0xD9B08C, 0xAD9A8A, 0x75655A, 0x554940))
    val LIGHT = ThemeSpec("light", "Light", pal(true, 0xF4F4F3, 0xFFFFFF, 0xF8F8F7, 0xECECEA, 0xDADAD8, 0xE8E8E6, 0xE0E0DE, 0xD0D0CE, 0x111112, 0x161617, 0x5C5C5E, 0x8A8A8C, 0xB0B0B2))
    val ICE = ThemeSpec("ice", "Ice", pal(true, 0xEEF3F7, 0xFFFFFF, 0xF6F9FB, 0xE3EBF1, 0xCCD9E3, 0xE1E8EE, 0xD9E2E9, 0xC6D3DE, 0x0E1620, 0x2F6FA8, 0x52606E, 0x84919D, 0xAAB5BF))
    val LILAC = ThemeSpec("lilac", "Lilac", pal(true, 0xF3F1F7, 0xFFFFFF, 0xF9F8FC, 0xE9E5F1, 0xD6D0E4, 0xE6E2EE, 0xDEDAE8, 0xCDC6DC, 0x16121F, 0x6B55B8, 0x5E5770, 0x8E879F, 0xB2ACC0))
    val SNOW = ThemeSpec("snow", "Snow", pal(true, 0xFFFFFF, 0xF6F6F6, 0xF2F2F2, 0xEBEBEB, 0xD9D9D9, 0xEDEDED, 0xE6E6E6, 0xD6D6D6, 0x0A0A0A, 0x0A0A0A, 0x5A5A5A, 0x8C8C8C, 0xB4B4B4))
    val PAPER = ThemeSpec("paper", "Paper", pal(true, 0xF7F5F0, 0xFFFEFB, 0xFBF9F5, 0xEDE9E1, 0xDCD6CB, 0xE9E5DD, 0xE2DDD4, 0xD2CBBF, 0x1B1915, 0x2B2823, 0x625C52, 0x938C80, 0xB9B2A6))
    val LINEN = ThemeSpec("linen", "Linen", pal(true, 0xF6F0E6, 0xFFFBF4, 0xFBF6ED, 0xEFE5D5, 0xE0D2BC, 0xEAE1D2, 0xE3D8C6, 0xD5C6AE, 0x231B12, 0x9A6B3F, 0x6B5D4C, 0x9A8B78, 0xBEB09C))
    val SKY = ThemeSpec("sky", "Sky", pal(true, 0xEEF5FC, 0xFFFFFF, 0xF6FAFE, 0xE1ECF8, 0xC8DAF0, 0xE0EAF5, 0xD7E3F1, 0xC2D4EA, 0x0C1724, 0x1F7AE0, 0x4F6177, 0x8193A8, 0xA9B8C9))
    val BLUSH = ThemeSpec("blush", "Blush", pal(true, 0xFBF1F3, 0xFFFFFF, 0xFEF8F9, 0xF5E3E7, 0xEAC9D1, 0xF2E3E7, 0xECD9DE, 0xE0C5CC, 0x22131A, 0xC2577A, 0x705A62, 0xA08A92, 0xC2AFB6))
    val PERI = ThemeSpec("peri", "Periwinkle", pal(true, 0xF0F1FB, 0xFFFFFF, 0xF8F8FE, 0xE5E7F7, 0xCDD1F0, 0xE3E5F5, 0xDADDF2, 0xC8CCEA, 0x12142A, 0x5865F2, 0x565A78, 0x888CA8, 0xAEB1C8))
    val dark: List<ThemeSpec> = listOf(MONO, OLED, MIDNIGHT, SAND, VIOLET, OCEAN, ROSE, SLATE, MOCHA)
    val light: List<ThemeSpec> = listOf(LIGHT, ICE, LILAC, SNOW, PAPER, LINEN, SKY, BLUSH, PERI)
    val all: List<ThemeSpec> = dark + light

    /** Unknown or missing ids fall back to Mono (a theme removed in a later build never breaks Settings). */
    fun byId(id: String?): ThemeSpec = all.firstOrNull { it.id == id } ?: MONO
}

/** Text size steps on Settings › Appearance; [DEFAULT] is the 0.5.5 (smaller) scale. */
object TextSizes {
    val labels = listOf("Small", "Default", "Large", "Larger")
    private val scales = listOf(0.9f, 1f, 1.12f, 1.25f)
    const val DEFAULT = 1

    fun scale(step: Int): Float = scales[step.coerceIn(0, scales.lastIndex)]
}

/** What the user picked on Settings › Appearance (persisted in AppSettings). */
data class Appearance(
    val themeId: String = Themes.MONO.id,
    val followSystem: Boolean = false,
    val lightId: String = Themes.LIGHT.id,
    val darkId: String = Themes.MONO.id,
    val textSize: Int = TextSizes.DEFAULT,
) {
    /** The theme in use: the fixed pick, or the light / dark pick when following the system. */
    fun resolve(systemDark: Boolean): ThemeSpec = if (followSystem) {
        val id = if (systemDark) darkId else lightId
        Themes.byId(id).takeIf { it.palette.light == !systemDark } ?: if (systemDark) Themes.MONO else Themes.LIGHT
    } else {
        Themes.byId(themeId)
    }

    /** Picking a theme card: with Follow system on it sets the light or the dark slot, else the theme. */
    fun pick(id: String): Appearance {
        val spec = Themes.byId(id)
        return when {
            !followSystem -> copy(themeId = spec.id)
            spec.palette.light -> copy(lightId = spec.id)
            else -> copy(darkId = spec.id)
        }
    }

    fun isSelected(id: String): Boolean = if (followSystem) id == lightId || id == darkId else id == themeId

    val fontScale: Float get() = TextSizes.scale(textSize)
}

/**
 * The palette on screen. Snapshot state, so composition and drawing (Canvas, drawBehind) both pick
 * up a change; TetherTheme animates it on a theme switch (a short colour cross-fade).
 */
object ThemeState {
    var palette: TetherPalette by mutableStateOf(Themes.MONO.palette)
}
