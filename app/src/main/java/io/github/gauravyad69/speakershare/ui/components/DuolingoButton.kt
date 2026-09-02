package io.github.gauravyad69.speakershare.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gauravyad69.speakershare.ui.theme.DuoGreen
import io.github.gauravyad69.speakershare.ui.theme.DuoGreenShadow

@Composable
fun DuolingoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = DuoGreen,
    shadowColor: Color = DuoGreenShadow,
    textColor: Color = Color.White,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 50.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // If disabled, use grey colors
    val activeColor = if (enabled) color else Color(0xFF52656D)
    val activeShadow = if (enabled) shadowColor else Color(0xFF37464F)
    val activeText = if (enabled) textColor else Color(0xFF777777)

    val cornerRadius = 16.dp
    val shadowHeight = 4.dp
    
    val topOffset = if (isPressed) shadowHeight else 0.dp

    Box(
        modifier = modifier
            .height(height + shadowHeight)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // No ripple, we animate movement
                enabled = enabled,
                onClick = onClick
            )
    ) {
        // Shadow Layer (Bottom)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = shadowHeight)
                .clip(RoundedCornerShape(cornerRadius))
                .background(activeShadow)
        )

        // Surface Layer (Top)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = topOffset)
                .clip(RoundedCornerShape(cornerRadius))
                .background(activeColor)
                .border(
                    width = 0.dp, // No border for now, maybe add later if needed
                    color = Color.Transparent,
                    shape = RoundedCornerShape(cornerRadius)
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = activeText,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = text.uppercase(),
                    color = activeText,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
