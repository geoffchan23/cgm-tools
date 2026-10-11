package com.geoffchan.glucosewidget

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement

/**
 * Sugar.AI's look, taken from the app icon: indigo (Geoff's pick,
 * 2026-10-10; was orange), the sparkle's cream and the face's cocoa, on
 * cool indigo-tinted neutrals. Light and dark follow the phone. Glucose
 * status colours (low / high) are deliberately separate from the brand.
 */
object Brand {
    val Sugar = Color(0xFF4F46E5)
    val SugarDeep = Color(0xFF4338CA)
    val Sparkle = Color(0xFFFFF1B8)
    val Cocoa = Color(0xFF3A2318)
    val Low = Color(0xFFE5484D)
    val HighLight = Color(0xFFC98A00)
    val HighDark = Color(0xFFFFC14D)
    val Stale = Color(0xFF9A8B83)
}

/** Colours the Material scheme has no slot for. */
@Immutable
data class SugarColors(
    val ink: Color,       // in-range dots, primary text on the chart
    val muted: Color,     // axis labels, secondary text
    val grid: Color,
    val band: Color,      // target range fill
    val low: Color,
    val high: Color,
    val dose: Color,
    val event: Color,
    val soft: Color,      // indigo-tinted chip / icon backgrounds
    val onSoft: Color,
)

private val LightSugar = SugarColors(
    ink = Color(0xFF1B1B2F), muted = Color(0xFF6B6C85), grid = Color(0x121B1B2F),
    band = Color(0x1A2FA36B), low = Brand.Low, high = Brand.HighLight,
    dose = Brand.SugarDeep, event = Color(0xFF0E9F8E), soft = Color(0xFFE0E7FF), onSoft = Color(0xFF3730A3),
)
private val DarkSugar = SugarColors(
    ink = Color(0xFFECECF7), muted = Color(0xFF9A9BB5), grid = Color(0x14ECECF7),
    band = Color(0x292FA36B), low = Color(0xFFFF6B6E), high = Brand.HighDark,
    dose = Color(0xFF8B8CF8), event = Color(0xFF5EEAD4), soft = Color(0xFF2E2B6B), onSoft = Color(0xFFA5B4FC),
)

val LocalSugar = staticCompositionLocalOf { DarkSugar }

private val LightScheme = lightColorScheme(
    primary = Brand.SugarDeep, onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF6B6C85), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEFFA), onSecondaryContainer = Color(0xFF1B1B2F),
    tertiary = Color(0xFF0E9F8E), onTertiary = Color.White,
    background = Color(0xFFF7F7FB), onBackground = Color(0xFF1B1B2F),
    surface = Color(0xFFF7F7FB), onSurface = Color(0xFF1B1B2F),
    surfaceVariant = Color(0xFFEEEFFA), onSurfaceVariant = Color(0xFF6B6C85),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFBFE),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF1F2FB), surfaceContainerHighest = Color(0xFFE9EAF8),
    outline = Color(0xFFD9DAEE), outlineVariant = Color(0xFFE8E9F5),
    error = Brand.Low, onError = Color.White,
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8B8CF8), onPrimary = Color(0xFF15133A),
    primaryContainer = Color(0xFF2E2B6B), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF9A9BB5), onSecondary = Color(0xFF0F0F1A),
    secondaryContainer = Color(0xFF22223A), onSecondaryContainer = Color(0xFFECECF7),
    tertiary = Color(0xFF5EEAD4), onTertiary = Color(0xFF1B1B2F),
    background = Color(0xFF0F0F1A), onBackground = Color(0xFFECECF7),
    surface = Color(0xFF0F0F1A), onSurface = Color(0xFFECECF7),
    surfaceVariant = Color(0xFF22223A), onSurfaceVariant = Color(0xFF9A9BB5),
    surfaceContainerLowest = Color(0xFF0A0A12), surfaceContainerLow = Color(0xFF14141F),
    surfaceContainer = Color(0xFF181828), surfaceContainerHigh = Color(0xFF1F1F33), surfaceContainerHighest = Color(0xFF26263D),
    outline = Color(0xFF3A3A55), outlineVariant = Color(0xFF26263D),
    error = Color(0xFFFF6B6E), onError = Color(0xFF2A0A0B),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun nunito(w: Int) = Font(
    R.font.nunito, weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800), nunito(900))

private val SugarType: Typography = Typography().let { t ->
    fun androidx.compose.ui.text.TextStyle.n(w: Int = 600) = copy(fontFamily = Nunito, fontWeight = FontWeight(w))
    Typography(
        displayLarge = t.displayLarge.n(900), displayMedium = t.displayMedium.n(900), displaySmall = t.displaySmall.n(800),
        headlineLarge = t.headlineLarge.n(800), headlineMedium = t.headlineMedium.n(800), headlineSmall = t.headlineSmall.n(800),
        titleLarge = t.titleLarge.n(800), titleMedium = t.titleMedium.n(800), titleSmall = t.titleSmall.n(700),
        bodyLarge = t.bodyLarge.n(600), bodyMedium = t.bodyMedium.n(600), bodySmall = t.bodySmall.n(600),
        labelLarge = t.labelLarge.n(800), labelMedium = t.labelMedium.n(700), labelSmall = t.labelSmall.n(700),
    )
}

@Composable
fun SugarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSugar provides if (dark) DarkSugar else LightSugar) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = SugarType, content = content)
    }
}

/** Ray's face: the app icon's cube on its indigo circle. */
@Composable
fun RayAvatar(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ray_avatar), contentDescription = "Ray", modifier = modifier.size(size))
}

/**
 * A bottom sheet with AlertDialog's slots (title, body, buttons), so the
 * app's dialogs open from the bottom within thumb reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable ColumnScope.() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            title?.let {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleLarge) { it() }
            }
            text?.let { body ->
                Column(Modifier.weight(1f, fill = false)) { body() }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

/** Small section heading used across screens ("Today's log", "Reliability"). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
