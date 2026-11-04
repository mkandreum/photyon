package com.example.ui.components

import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Darkroom
import com.example.ui.theme.WordmarkStyle
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/* ---- Shapes ---- */

val CardShape = RoundedCornerShape(16.dp)
val PillShape = RoundedCornerShape(percent = 50)
val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/** Camera iris: a softly rounded octagon, the brand motif for icon buttons and the primary action. */
class ApertureShape(private val sides: Int = 8, private val cornerFraction: Float = 0.22f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) / 2f / cos(Math.PI / sides).toFloat()
        val points = List(sides) { i ->
            val a = Math.toRadians(360.0 / sides * i + 180.0 / sides)
            Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }
        val edge = (points[1] - points[0]).getDistance()
        val corner = edge * cornerFraction
        val path = Path()
        for (i in 0 until sides) {
            val prev = points[(i + sides - 1) % sides]
            val cur = points[i]
            val next = points[(i + 1) % sides]
            val a = cur + (prev - cur).unit() * corner
            val b = cur + (next - cur).unit() * corner
            if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
            path.quadraticTo(cur.x, cur.y, b.x, b.y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private fun Offset.unit(): Offset {
        val len = sqrt(x * x + y * y)
        return if (len == 0f) this else Offset(x / len, y / len)
    }
}

val Aperture = ApertureShape()

fun Modifier.floatingShadow(shape: Shape = PillShape, elevation: Dp = 18.dp): Modifier =
    shadow(elevation, shape, clip = false, ambientColor = Darkroom.Shadow, spotColor = Darkroom.Shadow)

/* ---- Haptics ---- */

class Haptics(private val view: View) {
    private val vibrator: Vibrator? = view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private fun play(fallback: Int, vararg primitives: Pair<Int, Float>) {
        val v = vibrator
        val ids = primitives.map { it.first }.toIntArray()
        if (v != null && view.isHapticFeedbackEnabled && v.areAllPrimitivesSupported(*ids)) {
            val composition = VibrationEffect.startComposition()
            primitives.forEach { (id, scale) -> composition.addPrimitive(id, scale) }
            v.vibrate(composition.compose())
        } else {
            view.performHapticFeedback(fallback)
        }
    }

    fun tick() = view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    fun press() = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    fun confirm() = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    fun reject() = view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    fun longPress() = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

    /** A photo lifting towards you. */
    fun open() = play(HapticFeedbackConstants.GESTURE_START, VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.7f, VibrationEffect.Composition.PRIMITIVE_CLICK to 0.5f)

    /** A photo settling back. */
    fun close() = play(HapticFeedbackConstants.GESTURE_END, VibrationEffect.Composition.PRIMITIVE_QUICK_FALL to 0.7f)

    /** Moving on to the next photo. */
    fun page() = play(HapticFeedbackConstants.SEGMENT_TICK, VibrationEffect.Composition.PRIMITIVE_TICK to 0.8f)

    /** Dragged past the point where letting go commits. */
    fun threshold() = play(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, VibrationEffect.Composition.PRIMITIVE_THUD to 0.6f)

    /** A soft landing: favorites, zoom in. */
    fun pop() = play(HapticFeedbackConstants.CONFIRM, VibrationEffect.Composition.PRIMITIVE_SPIN to 0.5f)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/* ---- Motion ---- */

/** Signature press: spring scale, haptic, no ripple. */
@Composable
fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    feedback: (Haptics) -> Unit = { it.press() },
    onClick: () -> Unit
): Modifier {
    val haptics = rememberHaptics()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = if (pressed) 1f else 0.5f,
            stiffness = if (pressed) Spring.StiffnessHigh else Spring.StiffnessMediumLow
        ),
        label = "press"
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(enabled = enabled, interactionSource = source, indication = null) {
            feedback(haptics)
            onClick()
        }
}

/* ---- Controls ---- */

@Composable
fun ApertureButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    container: Color = Darkroom.Chip,
    tint: Color = Darkroom.Ink
) {
    Box(
        modifier = modifier
            .size(size)
            .bounceClick(pressedScale = 0.86f, onClick = onClick)
            .clip(Aperture)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .bounceClick(enabled = enabled, pressedScale = 0.97f, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(PillShape)
            .background(Darkroom.Accent)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, tint = Darkroom.OnAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Darkroom.OnAccent)
    }
}

@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    danger: Boolean = false
) {
    val content = if (danger) Darkroom.Danger else Darkroom.Ink
    Row(
        modifier = modifier
            .height(52.dp)
            .bounceClick(pressedScale = 0.96f, onClick = onClick)
            .clip(PillShape)
            .background(if (danger) Darkroom.DangerSoft else Darkroom.Raised)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Darkroom.Accent else Darkroom.Chip, label = "chipBg")
    val fg by animateColorAsState(if (selected) Darkroom.OnAccent else Darkroom.Ink, label = "chipFg")
    Box(
        modifier = modifier
            .height(40.dp)
            .bounceClick(pressedScale = 0.92f, feedback = { it.tick() }, onClick = onClick)
            .clip(PillShape)
            .background(bg)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(color = fg), maxLines = 1)
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Darkroom.Ink)) { append("photyon") }
            withStyle(SpanStyle(color = Darkroom.Accent)) { append(".") }
        },
        style = WordmarkStyle,
        maxLines = 1,
        modifier = modifier
    )
}

/* ---- Floating navigation ---- */

data class NavItem(val route: String, val label: String, val icon: ImageVector)

/** Floating pill: the active tab opens up with its name on a flat wash of safelight. */
@Composable
fun FloatingNav(
    items: List<NavItem>,
    current: String,
    onSelect: (String) -> Unit,
    primaryIcon: ImageVector,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .floatingShadow(PillShape, 24.dp)
            .clip(PillShape)
            .background(Darkroom.Nav)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { item ->
            NavButton(item, selected = item.route == current, onClick = { onSelect(item.route) })
        }
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(52.dp)
                .bounceClick(pressedScale = 0.85f, onClick = onPrimary)
                .clip(Aperture)
                .background(Darkroom.Accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(primaryIcon, primaryLabel, tint = Darkroom.OnAccent, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun NavButton(item: NavItem, selected: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (selected) Darkroom.Ink else Darkroom.Muted, label = "navTint")
    val light by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "navLight"
    )
    Row(
        modifier = Modifier
            .height(52.dp)
            .bounceClick(pressedScale = 0.9f, feedback = { it.tick() }, onClick = onClick)
            .clip(PillShape)
            .drawBehind { if (light > 0f) drawRect(Darkroom.Accent.copy(alpha = 0.22f * light)) }
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(23.dp))
        if (selected) {
            Text(
                item.label,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/* ---- Small dot used on tiles ---- */

@Composable
fun SelectionDot(selected: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val bg by animateColorAsState(if (selected) Darkroom.Accent else Color(0x66000000), label = "dot")
    val scale by animateFloatAsState(
        if (selected) 1f else 0.86f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "dotScale"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check, "Seleccionada",
                tint = Darkroom.OnAccent, modifier = Modifier.size(size * 0.66f)
            )
        }
    }
}
