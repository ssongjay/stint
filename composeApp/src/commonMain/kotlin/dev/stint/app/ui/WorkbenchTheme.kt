package dev.stint.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.stint.composeapp.generated.resources.Res
import dev.stint.composeapp.generated.resources.manrope_variable
import dev.stint.composeapp.generated.resources.noto_sans_kr_variable
import org.jetbrains.compose.resources.Font

internal val Paper = Color(0xFFF5F0E6)
internal val PaperRaised = Color(0xFFFBF7EE)
internal val PaperDeep = Color(0xFFEAE2D3)
internal val Ink = Color(0xFF1B2822)
internal val InkMuted = Color(0xFF626E65)
internal val Forest = Color(0xFF466B57)
internal val ForestDeep = Color(0xFF294B3A)
internal val ForestWash = Color(0xFFD9E4DA)
internal val Clay = Color(0xFF9F543D)
internal val ClayWash = Color(0xFFF0D8CC)
internal val Rule = Color(0xFFD2C8B8)
internal val ErrorRed = Color(0xFF9B473D)

private val WorkbenchColors = lightColorScheme(
    primary = Clay,
    onPrimary = PaperRaised,
    primaryContainer = ClayWash,
    onPrimaryContainer = Ink,
    secondary = Forest,
    onSecondary = PaperRaised,
    secondaryContainer = ForestWash,
    onSecondaryContainer = ForestDeep,
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkMuted,
    outline = Rule,
    outlineVariant = Rule.copy(alpha = 0.58f),
    error = ErrorRed,
    onError = PaperRaised,
)

@Composable
private fun workbenchTypography(): Typography {
    val body = FontFamily(Font(Res.font.noto_sans_kr_variable))
    val display = FontFamily(Font(Res.font.manrope_variable))
    return Typography(
        displayLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 76.sp, lineHeight = 78.sp, letterSpacing = (-3).sp),
        displayMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 58.sp, lineHeight = 60.sp, letterSpacing = (-2).sp),
        headlineLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 39.sp, letterSpacing = (-0.8).sp),
        headlineMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
        titleLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    )
}

@Composable
internal fun WorkbenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WorkbenchColors,
        typography = workbenchTypography(),
        content = content,
    )
}
