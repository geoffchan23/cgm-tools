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
 * Sugar.AI's look, taken from the app icon: sugar orange, the sparkle's
 * cream and the face's cocoa. Light and dark follow the phone. Glucose
 * status colours (low / high) are deliberately separate from the brand.
 */
object Brand {
    val Sugar = Color(0xFFFF7A45)
    val SugarDeep = Color(0xFFE8622C) // text/buttons on white: plain Sugar is too faint
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
    val soft: Color,      // orange-tinted chip / icon backgrounds
    val onSoft: Color,
)

private val LightSugar = SugarColors(
    ink = Color(0xFF2A1D17), muted = Color(0xFF8A776D), grid = Color(0x122A1D17),
    band = Color(0x1A2FA36B), low = Brand.Low, high = Brand.HighLight,
    dose = Brand.SugarDeep, event = Color(0xFF8B5E3C), soft = Color(0xFFFFE6DA), onSoft = Color(0xFFC2410C),
)
private val DarkSugar = SugarColors(
    ink = Color(0xFFF6ECE6), muted = Color(0xFFA8968C), grid = Color(0x14F6ECE6),
    band = Color(0x292FA36B), low = Color(0xFFFF6B6E), high = Brand.HighDark,
    dose = Color(0xFFFF8A5B), event = Color(0xFFE8C9A8), soft = Color(0xFF4A2414), onSoft = Color(0xFFFFB08A),
)

val LocalSugar = staticCompositionLocalOf { DarkSugar }

private val LightScheme = lightColorScheme(
    primary = Brand.SugarDeep, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE6DA), onPrimaryContainer = Color(0xFF6B2A0E),
    secondary = Color(0xFF8A776D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF1EA), onSecondaryContainer = Color(0xFF2A1D17),
    tertiary = Color(0xFF8B5E3C), onTertiary = Color.White,
    background = Color(0xFFFFF9F5), onBackground = Color(0xFF2A1D17),
    surface = Color(0xFFFFF9F5), onSurface = Color(0xFF2A1D17),
    surfaceVariant = Color(0xFFFFF1EA), onSurfaceVariant = Color(0xFF8A776D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFCFA),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFFFF4EE), surfaceContainerHighest = Color(0xFFFFEDE4),
    outline = Color(0xFFE2CFC4), outlineVariant = Color(0xFFF1E4DC),
    error = Brand.Low, onError = Color.White,
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF8A5B), onPrimary = Color(0xFF2A1208),
    primaryContainer = Color(0xFF4A2414), onPrimaryContainer = Color(0xFFFFD9C7),
    secondary = Color(0xFFA8968C), onSecondary = Color(0xFF17100D),
    secondaryContainer = Color(0xFF2C1F19), onSecondaryContainer = Color(0xFFF6ECE6),
    tertiary = Color(0xFFE8C9A8), onTertiary = Color(0xFF2A1D17),
    background = Color(0xFF17100D), onBackground = Color(0xFFF6ECE6),
    surface = Color(0xFF17100D), onSurface = Color(0xFFF6ECE6),
    surfaceVariant = Color(0xFF2C1F19), onSurfaceVariant = Color(0xFFA8968C),
    surfaceContainerLowest = Color(0xFF120C0A), surfaceContainerLow = Color(0xFF1C1411),
    surfaceContainer = Color(0xFF221814), surfaceContainerHigh = Color(0xFF2A1E19), surfaceContainerHighest = Color(0xFF33251F),
    outline = Color(0xFF4A3830), outlineVariant = Color(0xFF33251F),
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

/** Ray's face: the app icon's cube on its orange circle. */
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
