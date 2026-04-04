package org.des.vesta.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.des.vesta.R

val Syne = FontFamily(
    Font(R.font.synereg, FontWeight.Normal),
    Font(R.font.fallbackcyrillic, FontWeight.Normal),
    Font(R.font.synemed, FontWeight.Medium),
    Font(R.font.synesemi, FontWeight.SemiBold),
    Font(R.font.synebold, FontWeight.Bold),
    Font(R.font.synexbold, FontWeight.ExtraBold),
    Font(R.font.fallbackcyrillicheader, FontWeight.ExtraBold)
)

val Typography = Typography(
    displayLarge = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 57.sp),
    displayMedium = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 45.sp),
    displaySmall = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp),
    headlineLarge = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Syne, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Syne, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Syne, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Syne, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Syne, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Syne, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Syne, fontWeight = FontWeight.Medium, fontSize = 11.sp)
)
