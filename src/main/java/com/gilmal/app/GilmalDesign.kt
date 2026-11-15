package com.gilmal.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────────────────────────────────────────────────────────────
// 길말 차량용 디자인 시스템
// - 차량 실내(야간 포함)에서 눈부심이 적은 짙은 남색 계열 바탕
// - 강조색은 한 가지(파랑)만 쓰고, 위험 단계는 신호색(초록·노랑·주황·빨강)으로만 표현
// - 모든 화면의 Material 버튼·카드·탭이 이 색을 따라가므로 기존 테스트 화면도 함께 정돈된다
// ───────────────────────────────────────────────────────────────

object GilmalColors {
    val Background = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val SurfaceHigh = Color(0xFF1E2530)
    val SurfaceHigher = Color(0xFF283140)
    val Outline = Color(0xFF34404F)
    val TextPrimary = Color(0xFFEFF3F8)
    val TextSecondary = Color(0xFFB4BECB)
    val TextMuted = Color(0xFF7F8A99)
    val Accent = Color(0xFF4C8DFF)
    val AccentSoft = Color(0xFF1C3358)

    val Calm = Color(0xFF2FA866)
    val Attention = Color(0xFFE3B341)
    val Caution = Color(0xFFF0883E)
    val Severe = Color(0xFFE5484D)
    val Unknown = Color(0xFF5E6A7A)
}

private val GilmalColorScheme = darkColorScheme(
    primary = GilmalColors.Accent,
    onPrimary = Color.White,
    primaryContainer = GilmalColors.AccentSoft,
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF8FA3BF),
    onSecondary = GilmalColors.Background,
    secondaryContainer = GilmalColors.SurfaceHigher,
    onSecondaryContainer = GilmalColors.TextPrimary,
    tertiary = GilmalColors.Attention,
    background = GilmalColors.Background,
    onBackground = GilmalColors.TextPrimary,
    surface = GilmalColors.Surface,
    onSurface = GilmalColors.TextPrimary,
    surfaceVariant = GilmalColors.SurfaceHigh,
    onSurfaceVariant = GilmalColors.TextSecondary,
    surfaceContainer = GilmalColors.Surface,
    surfaceContainerHigh = GilmalColors.SurfaceHigh,
    surfaceContainerHighest = GilmalColors.SurfaceHigher,
    outline = GilmalColors.Outline,
    outlineVariant = Color(0xFF263040),
    error = GilmalColors.Severe,
    onError = Color.White,
    errorContainer = Color(0xFF4A1C1F),
    onErrorContainer = Color(0xFFFFD9DA)
)

private val GilmalTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
)

private val GilmalShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun GilmalCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GilmalColorScheme,
        typography = GilmalTypography,
        shapes = GilmalShapes,
        content = content
    )
}
