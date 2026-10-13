package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassBarFill
import com.example.ui.theme.GlassBarBorder
import com.example.ui.theme.GlassSpecular
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    borderAlpha: Float = 1f,
    backgroundAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        GlassSpecular.copy(alpha = GlassSpecular.alpha * borderAlpha),
                        GlassBarBorder.copy(alpha = GlassBarBorder.alpha * borderAlpha)
                    )
                ),
                shape = shape
            )
            .clip(shape)
            .background(GlassBarFill.copy(alpha = GlassBarFill.alpha * backgroundAlpha))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            propagateMinConstraints = true
        ) {
            content()
        }
    }
}

@Composable
fun LiquidLensCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "lens")
    val wavePhase = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "lensPhase"
    )

    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val rx = w * 0.49f
            val ry = h * 0.49f
            val ovalTl = Offset(cx - rx, cy - ry)
            val ovalSize = Size(rx * 2f, ry * 2f)
            val glassBrush = Brush.radialGradient(
                0f to Color(0x0AFFFFFF),
                0.55f to Color(0x0FFFFFFF),
                0.82f to Color(0x18FFFFFF),
                1f to Color(0x08FFFFFF),
                center = Offset(cx, cy),
                radius = maxOf(rx, ry)
            )
            val shadowBrush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.6f to Color.Black.copy(alpha = 0.15f),
                1f to Color.Black.copy(alpha = 0.3f),
                startY = cy + ry * 0.3f,
                endY = cy + ry
            )
            val specRx = rx * 0.35f
            val specRy = ry * 0.18f
            val specR = specRx * 2.5f
            val highlightBrush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.32f),
                0.3f to Color.White.copy(alpha = 0.12f),
                0.7f to Color.White.copy(alpha = 0.02f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = specR
            )
            val redFringe = Color(0x44FF3333)
            val blueFringe = Color(0x443366FF)
            val wavePaths = Array(3) { Path() }
            val arcPaths = Array(2) { Path() }
            val waveStroke = Array(3) { Stroke(width = 2.5f - it * 0.5f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
            val arcStroke = Array(2) { Stroke(width = 3f - it, cap = StrokeCap.Round) }
            val rimStroke = Stroke(width = 1.2f)
            val pi = PI.toFloat()

            onDrawWithContent {
                drawContent()
                val phase = wavePhase.value // read in draw phase only

                drawOval(redFringe, Offset(cx - rx - 1.5f, cy - ry), Size(rx * 2f + 3f, ry * 2f))
                drawOval(blueFringe, Offset(cx - rx + 1.5f, cy - ry), Size(rx * 2f - 3f, ry * 2f))
                drawOval(brush = glassBrush, topLeft = ovalTl, size = ovalSize)

                for (i in 0 until 3) {
                    val path = wavePaths[i]
                    path.rewind()
                    val startY = cy - ry * 0.7f + i * ry * 0.45f
                    val freq = 3f + i * 1.5f
                    val steps = 12
                    for (j in 0..steps) {
                        val nx = -0.65f + 1.3f * j / steps
                        val amp = ry * 0.06f * (1f - nx * nx).coerceAtLeast(0f)
                        val y = startY + amp * sin(nx * pi * freq + phase + i * 2.1f)
                        val x = cx + nx * rx
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Color.White.copy(alpha = 0.07f + i * 0.02f), style = waveStroke[i])
                }

                for (i in 0 until 2) {
                    val arcPath = arcPaths[i]
                    arcPath.rewind()
                    val arcCy = cy - ry * 0.3f + i * ry * 0.6f
                    val arcR = rx * 0.5f + i * rx * 0.15f
                    val segments = 12
                    for (j in 0..segments) {
                        val angle = -pi * 0.6f + j * pi * 1.2f / segments
                        val px = cx + arcR * cos(angle)
                        val py = arcCy + ry * 0.2f * sin(angle * 2.5f + phase * 0.7f + i)
                        if (j == 0) arcPath.moveTo(px, py) else arcPath.lineTo(px, py)
                    }
                    drawPath(arcPath, Color.White.copy(alpha = 0.05f + i * 0.03f), style = arcStroke[i])
                }

                val hx = 0.5f + 0.25f * sin(phase * 0.35f)
                val hy = 0.45f + 0.15f * cos(phase * 0.28f)
                val specCx = cx + (hx - 0.5f) * rx * 1.2f
                val specCy = cy + (hy - 0.45f) * ry * 1.1f
                translate(specCx, specCy) {
                    drawOval(brush = highlightBrush, topLeft = Offset(-specRx * 2.5f, -specRy * 3f), size = Size(specRx * 5f, specRy * 6f))
                    drawCircle(Color.White.copy(alpha = 0.45f), rx * 0.04f, Offset(-specRx * 0.3f, -specRy * 0.8f))
                }

                drawOval(
                    Color.White.copy(alpha = 0.18f),
                    Offset(cx - rx + 1f, cy - ry + 1f),
                    Size(rx * 2f - 2f, ry * 2f - 2f),
                    style = rimStroke
                )
                drawOval(brush = shadowBrush, topLeft = ovalTl, size = ovalSize)
            }
        }
    ) {
        content()
    }
}

@Composable
fun AnimatedPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            val dotScale = animateFloatAsState(
                targetValue = if (isSelected) 1.3f else 0.7f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                label = "dotScale"
            )
            val dotAlpha = animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.45f,
                animationSpec = tween(200),
                label = "dotAlpha"
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        scaleX = dotScale.value
                        scaleY = dotScale.value
                        alpha = dotAlpha.value
                    }
                    .background(color = Color.White, shape = CircleShape)
            )
        }
    }
}

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    label: String = ""
) {
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                Text(
                    String.format("%.0f", value),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f),
                inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AnimatedPressIcon(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    icon: @Composable (Modifier) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale = animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 900f),
        label = "pressScale"
    )
    val pressAlpha = animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 1f,
        animationSpec = tween(90),
        label = "pressAlpha"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale.value
                scaleY = pressScale.value
                alpha = pressAlpha.value
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        icon(Modifier)
    }
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    val shimmerTransition = rememberInfiniteTransition(label = "shimmerBox")
    val shimmerAlpha = shimmerTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .drawBehind { drawRect(Color.White, alpha = shimmerAlpha.value) }
    )
}
