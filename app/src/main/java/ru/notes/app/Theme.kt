package ru.notes.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Тема в стиле iOS: системные цвета Apple, крупный заголовок,
 * «группированный» серый фон с белыми карточками, крупные скругления.
 */
object Ios {
    val blue = Color(0xFF007AFF)        // systemBlue (светлая тема)
    val blueDark = Color(0xFF0A84FF)    // systemBlue (тёмная тема)
    val red = Color(0xFFFF3B30)         // systemRed
    val redDark = Color(0xFFFF453A)
    val grayBg = Color(0xFFF2F2F7)      // systemGroupedBackground
    val card = Color(0xFFFFFFFF)        // secondarySystemGroupedBackground
    val darkBg = Color(0xFF000000)
    val darkCard = Color(0xFF1C1C1E)
    val grayText = Color(0xFF8E8E93)    // secondaryLabel
    val separator = Color(0xFFC6C6C8)
    val separatorDark = Color(0xFF38383A)
}

private val IosLight = lightColorScheme(
    primary = Ios.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAFF),
    onPrimaryContainer = Ios.blue,
    secondary = Ios.grayText,
    onSecondary = Color.White,
    background = Ios.grayBg,
    onBackground = Color(0xFF1C1C1E),
    surface = Ios.card,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE3E3EA),
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceContainer = Ios.card,
    surfaceContainerLow = Ios.card,
    surfaceContainerHigh = Ios.card,
    outline = Ios.grayText,
    outlineVariant = Ios.separator,
    error = Ios.red,
    onError = Color.White,
)

private val IosDark = darkColorScheme(
    primary = Ios.blueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF12385E),
    onPrimaryContainer = Ios.blueDark,
    secondary = Ios.grayText,
    onSecondary = Color.Black,
    background = Ios.darkBg,
    onBackground = Color(0xFFF2F2F7),
    surface = Ios.darkCard,
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989D),
    surfaceContainer = Ios.darkCard,
    surfaceContainerLow = Ios.darkCard,
    surfaceContainerHigh = Ios.darkCard,
    outline = Ios.grayText,
    outlineVariant = Ios.separatorDark,
    error = Ios.redDark,
    onError = Color.Black,
)

private val IosTypography = Typography(
    // Large Title, как на iPhone
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.4).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp),
)

private val IosShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp), // диалоги
)

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) IosDark else IosLight,
        typography = IosTypography,
        shapes = IosShapes,
        content = content,
    )
}
