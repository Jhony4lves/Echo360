package com.jhony4lves.echo360.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val EchoColorScheme = darkColorScheme(
    primary = EchoColors.NeonGreen,
    onPrimary = EchoColors.Void,
    primaryContainer = EchoColors.XboxGreen,
    onPrimaryContainer = EchoColors.Text,
    secondary = EchoColors.Mint,
    onSecondary = EchoColors.Void,
    background = EchoColors.Void,
    onBackground = EchoColors.Text,
    surface = EchoColors.Surface,
    onSurface = EchoColors.Text,
    surfaceVariant = EchoColors.SurfaceHigh,
    onSurfaceVariant = EchoColors.TextSecondary,
    outline = EchoColors.Border,
    outlineVariant = EchoColors.BorderStrong,
    error = EchoColors.Error,
    onError = Color.Black,
)

private val EchoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.7.sp,
    ),
)

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EchoColorScheme,
        typography = EchoTypography,
        content = content,
    )
}