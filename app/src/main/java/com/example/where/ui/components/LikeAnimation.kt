package com.example.where.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun LikeAnimation(
    visible: Boolean,
    onAnimationEnd: () -> Unit = {}
) {
    var isAnimating by remember { mutableStateOf(false) }
    
    // Start animation when visible becomes true
    LaunchedEffect(visible) {
        if (visible) {
            isAnimating = true
            // Wait for both the scale and alpha animations to complete
            kotlinx.coroutines.delay(500) // Match the animation duration
            kotlinx.coroutines.delay(800) // Additional display time
            isAnimating = false
            // Only call onAnimationEnd if we're still visible
            // This prevents unnecessary state updates if the component is removed
            if (visible) {
                onAnimationEnd()
            }
        }
    }

    val animatedScale by animateFloatAsState(
        targetValue = if (isAnimating) 1f else 0f,
        animationSpec = keyframes {
            durationMillis = 500
            0f at 0
            1.5f at 100
            1f at 200
            1.2f at 300
            1f at 500
        }
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isAnimating) 1f else 0f,
        animationSpec = tween(
            durationMillis = 500,
            easing = LinearEasing
        )
    )

    if (isAnimating) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(150.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Like",
                tint = Color.Red,
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        alpha = animatedAlpha
                    }
            )
        }
    }
} 