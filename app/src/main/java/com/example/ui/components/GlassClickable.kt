package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** Tap target with liquid-glass press feedback (slight scale + dim), no ripple. */
fun Modifier.glassClickable(pressedScale: Float = 0.94f, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "glassPress"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) 0.8f else 1f }
        .clickable(interactionSource = source, indication = null, onClick = onClick)
}
