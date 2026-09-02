package io.github.gauravyad69.speakershare.ui.theme

import androidx.compose.ui.graphics.Color

// Duolingo-inspired Dark Palette
val DuoBackground = Color(0xFF131F24)
val DuoSurface = Color(0xFF202F36)
val DuoSurfaceHighlight = Color(0xFF37464F)

// Primary - Green (Action/Success)
val DuoGreen = Color(0xFF58CC02)
val DuoGreenShadow = Color(0xFF46A302)
val DuoGreenHighlight = Color(0xFF89E219)

// Secondary - Blue (Info/Navigation)
val DuoBlue = Color(0xFF1CB0F6)
val DuoBlueShadow = Color(0xFF1899D6)

// Accent - Purple
val DuoPurple = Color(0xFFCE82FF)
val DuoPurpleShadow = Color(0xFFA568CC)

// Error - Red
val DuoRed = Color(0xFFFF4B4B)
val DuoRedShadow = Color(0xFFD43F3F)

// Warning - Orange/Yellow
val DuoOrange = Color(0xFFFF9600)
val DuoOrangeShadow = Color(0xFFCC7800)

// Text
val DuoTextPrimary = Color(0xFFFFFFFF)
val DuoTextSecondary = Color(0xFFAFAFAF)
val DuoTextDisabled = Color(0xFF52656D)

// Outline
val DuoOutline = Color(0xFF37464F)

// Legacy/Material Mapping
val Primary = DuoGreen
val PrimaryLight = DuoGreenHighlight
val PrimaryDark = DuoGreenShadow
val OnPrimary = Color.White

val Secondary = DuoBlue
val SecondaryLight = Color(0xFF5AC8FA)
val SecondaryDark = DuoBlueShadow
val OnSecondary = Color.White

val Tertiary = DuoPurple
val TertiaryLight = Color(0xFFE0B3FF)
val TertiaryDark = DuoPurpleShadow

val Background = DuoBackground
val BackgroundDark = DuoBackground
val Surface = DuoSurface
val SurfaceDark = DuoSurface
val SurfaceVariant = DuoSurfaceHighlight
val SurfaceVariantDark = DuoSurfaceHighlight

val OnBackground = DuoTextPrimary
val OnBackgroundDark = DuoTextPrimary
val OnSurface = DuoTextPrimary
val OnSurfaceDark = DuoTextPrimary
val OnSurfaceVariant = DuoTextSecondary
val OnSurfaceVariantDark = DuoTextSecondary

val Success = DuoGreen
val Error = DuoRed
val Warning = Color(0xFFFF9800)
val Info = Color(0xFF2196F3)

// Streaming mode colors
val MicrophoneColor = Color(0xFF4CAF50)
val SystemAudioColor = Color(0xFF2196F3)
val ScreenAudioColor = Color(0xFF9C27B0)
val FilePlayerColor = Color(0xFFFF9800)
val VideoColor = Color(0xFFE91E63)

// Streaming theme colors (for player screens)
val StreamingPrimary = Color(0xFF00BCD4)  // Cyan
val StreamingSecondary = Color(0xFF00ACC1)  // Darker cyan
val StreamingTertiary = Color(0xFF26C6DA)  // Lighter cyan

// Gradient colors for dark theme
val GradientDarkStart = Color(0xFF1A1A2E)
val GradientDarkMid = Color(0xFF16213E)
val GradientDarkEnd = Color(0xFF0F3460)

// Accent colors for indicators
val LiveIndicator = Color(0xFFFF5252)
val OnlineIndicator = Color(0xFF00E676)
val OfflineIndicator = Color(0xFF757575)