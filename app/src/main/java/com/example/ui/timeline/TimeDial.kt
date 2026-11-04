package com.example.ui.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.components.PillShape
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.Anton
import com.example.ui.theme.Darkroom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Edge dial for jumping through time: appears while scrolling, and when grabbed spreads the years along
 * the rail, magnifies the ones near the finger and ticks on every month crossed.
 */
@Composable
fun TimeDial(state: TimelineState, modifier: Modifier = Modifier) {
    val anchors = state.rows.anchors
    if (state.rows.rows.size < 40 || anchors.isEmpty()) return

    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var heightPx by remember { mutableIntStateOf(1) }
    var grabbed by remember { mutableStateOf(false) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var anchorIndex by remember { mutableIntStateOf(0) }

    var recentlyScrolled by remember { mutableStateOf(false) }
    val scrolling by remember { derivedStateOf { state.list.isScrollInProgress } }
    LaunchedEffect(scrolling) {
        if (scrolling) recentlyScrolled = true
        else {
            delay(1400)
            recentlyScrolled = false
        }
    }
    val visible by animateFloatAsState(
        if (grabbed || recentlyScrolled) 1f else 0f,
        tween(180),
        label = "dial"
    )
    val spread by animateFloatAsState(if (grabbed) 1f else 0f, tween(160), label = "dialSpread")

    val rowCount = state.rows.rows.size
    val years = remember(anchors) { anchors.distinctBy { it.year } }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 96.dp)
            .width(if (grabbed) 220.dp else 44.dp)
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .pointerInput(rowCount) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        // Only the visible thumb area grabs, so edge swipes keep scrolling the list.
                        grabbed = true
                        fingerY = offset.y
                        haptics.longPress()
                    },
                    onDragEnd = { grabbed = false },
                    onDragCancel = { grabbed = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        fingerY = change.position.y.coerceIn(0f, heightPx.toFloat())
                        val row = (fingerY / heightPx * (rowCount - 1)).roundToInt()
                        val at = anchors.indexOfLast { it.rowIndex <= row }.coerceAtLeast(0)
                        if (at != anchorIndex) {
                            anchorIndex = at
                            haptics.tick()
                        }
                        scope.launch { state.list.scrollToItem(row + TimelineState.HEADER_ITEMS) }
                    }
                )
            }
            .semantics { contentDescription = "Dial de tiempo" }
    ) {
        if (visible > 0f) {
            // Year rail
            if (spread > 0f) {
                years.forEach { anchor ->
                    val y = anchor.rowIndex.toFloat() / rowCount * heightPx
                    val near = (1f - abs(y - fingerY) / (heightPx * 0.14f)).coerceIn(0f, 1f)
                    Text(
                        anchor.year.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (near > 0.6f) Darkroom.Accent else Darkroom.Muted,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(-34.dp.roundToPx(), y.roundToInt()) }
                            .graphicsLayer {
                                alpha = spread
                                val s = 1f + near * 0.5f
                                scaleX = s
                                scaleY = s
                                translationX = -near * 18.dp.toPx()
                            }
                    )
                }
            }
            // Thumb: reads scroll in the draw phase only, so scrolling never recomposes this composable.
            Canvas(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(24.dp)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = visible }
            ) {
                val first = if (grabbed) fingerY / heightPx else state.list.firstVisibleItemIndex.toFloat() / rowCount
                val thumbH = 44.dp.toPx()
                val x = size.width - 6.dp.toPx()
                val top = (first * (size.height - thumbH)).coerceIn(0f, size.height - thumbH)
                drawLine(
                    Darkroom.Accent,
                    Offset(x, top),
                    Offset(x, top + thumbH),
                    strokeWidth = 5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
            // Bubble with the month under the finger
            if (grabbed) {
                val anchor = anchors[anchorIndex.coerceIn(0, anchors.lastIndex)]
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-92.dp.roundToPx(), (fingerY - 32.dp.toPx()).roundToInt()) }
                        .wrapContentSize()
                        .clip(PillShape)
                        .background(Darkroom.Accent)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            anchor.month,
                            style = MaterialTheme.typography.titleLarge.copy(fontFamily = Anton),
                            color = Darkroom.OnAccent
                        )
                        Text(anchor.year.toString(), style = MaterialTheme.typography.labelSmall, color = Darkroom.OnAccent)
                    }
                }
            }
        }
    }
}
