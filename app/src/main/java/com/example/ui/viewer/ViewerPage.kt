package com.example.ui.viewer

import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.data.MediaItem
import com.example.data.Thumb
import com.example.ui.components.Aperture
import com.example.ui.components.bounceClick
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.Darkroom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun ViewerPage(
    item: MediaItem,
    active: Boolean,
    chromeVisible: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalEnd: (Float) -> Unit
) {
    if (item.isVideo) {
        VideoPage(item, active, chromeVisible, onTap, onVerticalDrag, onVerticalEnd)
    } else {
        PhotoPage(item, active, onZoomChanged, onTap, onVerticalDrag, onVerticalEnd)
    }
}

/** One-finger vertical swipes, pinch and pan share one detector; horizontal swipes stay with the pager. */
private fun Modifier.mediaGestures(
    zoomEnabled: Boolean,
    scale: () -> Float,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onTransformEnd: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalEnd: (Float) -> Unit
): Modifier = pointerInput(zoomEnabled) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var mode = 0 // 0 undecided, 1 vertical, 2 transform, 3 left to the pager
        var total = Offset.Zero
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (mode == 0 || mode == 3) {
                if (zoomEnabled && fingers >= 2) mode = 2
                else if (mode == 0 && zoomEnabled && scale() > 1.01f) mode = 2
            }
            when (mode) {
                0 -> {
                    total += event.changes.first().positionChange()
                    if (total.getDistance() > slop) {
                        mode = if (abs(total.y) > abs(total.x) * 1.2f) 1 else 3
                    }
                }
                1 -> {
                    val change = event.changes.first()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    onVerticalDrag(change.positionChange().y)
                    change.consume()
                }
                2 -> {
                    onTransform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        when (mode) {
            1 -> onVerticalEnd(tracker.calculateVelocity().y)
            2 -> onTransformEnd()
        }
    }
}

@Composable
private fun PhotoPage(
    item: MediaItem,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalEnd: (Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zoom = remember(item.id) { Animatable(1f) }
    val panX = remember(item.id) { Animatable(0f) }
    val panY = remember(item.id) { Animatable(0f) }
    var boxW by remember { mutableFloatStateOf(1f) }
    var boxH by remember { mutableFloatStateOf(1f) }
    val updatedTap by rememberUpdatedState(onTap)
    val haptics = rememberHaptics()

    // Leaving a page always resets its zoom.
    LaunchedEffect(active) {
        if (!active && zoom.value != 1f) {
            onZoomChanged(false)
            launch { panX.animateTo(0f, spring(dampingRatio = 1f, stiffness = 700f)) }
            launch { panY.animateTo(0f, spring(dampingRatio = 1f, stiffness = 700f)) }
            zoom.animateTo(1f, spring(dampingRatio = 1f, stiffness = 700f))
        }
    }

    val request = remember(item.id, item.dateModifiedSec) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .size(2880)
            .precision(Precision.INEXACT)
            // Shows the grid's thumbnail instantly while the full image decodes.
            .placeholderMemoryCacheKey(Thumb(item).cacheKey)
            .build()
    }
    fun clampPan(x: Float, y: Float, scale: Float): Offset {
        val maxX = (boxW * (scale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (boxH * (scale - 1f) / 2f).coerceAtLeast(0f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { updatedTap() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            val target = if (zoom.value > 1.01f) 1f else 2.6f
                            val center = Offset(boxW / 2f, boxH / 2f)
                            val focus = clampPan((center.x - tap.x) * (target - 1f), (center.y - tap.y) * (target - 1f), target)
                            onZoomChanged(target > 1f)
                            haptics.pop()
                            launch { zoom.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 420f)) }
                            launch { panX.animateTo(focus.x, spring(dampingRatio = 0.8f, stiffness = 420f)) }
                            launch { panY.animateTo(focus.y, spring(dampingRatio = 0.8f, stiffness = 420f)) }
                        }
                    }
                )
            }
            .mediaGestures(
                zoomEnabled = true,
                scale = { zoom.value },
                onTransform = { centroid, pan, factor ->
                    scope.launch {
                        val scale = (zoom.value * factor).coerceIn(0.85f, 8f)
                        if (scale == 8f && zoom.value < 8f) haptics.threshold()
                        val center = Offset(boxW / 2f, boxH / 2f)
                        val applied = scale / zoom.value
                        // Keep the point under the fingers fixed.
                        val x = panX.value * applied + (centroid.x - center.x) * (1f - applied) + pan.x
                        val y = panY.value * applied + (centroid.y - center.y) * (1f - applied) + pan.y
                        val clamped = clampPan(x, y, max(scale, 1f))
                        zoom.snapTo(scale)
                        panX.snapTo(clamped.x)
                        panY.snapTo(clamped.y)
                        onZoomChanged(scale > 1.01f)
                    }
                },
                onTransformEnd = {
                    scope.launch {
                        if (zoom.value < 1f) {
                            haptics.tick()
                            launch { zoom.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
                            launch { panX.animateTo(0f) }
                            launch { panY.animateTo(0f) }
                            onZoomChanged(false)
                        }
                    }
                },
                onVerticalDrag = onVerticalDrag,
                onVerticalEnd = onVerticalEnd
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    boxW = size.width
                    boxH = size.height
                    scaleX = zoom.value
                    scaleY = zoom.value
                    translationX = panX.value
                    translationY = panY.value
                }
        )
    }
}

@Composable
private fun VideoPage(
    item: MediaItem,
    active: Boolean,
    chromeVisible: Boolean,
    onTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalEnd: (Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableFloatStateOf(0f) }
    var firstFrame by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id, active) {
        if (!active) null else ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
            }
        }
        player?.addListener(listener)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.release()
            firstFrame = false
        }
    }
    LaunchedEffect(player, chromeVisible) {
        while (player != null && chromeVisible) {
            val duration = player.duration.takeIf { it > 0 } ?: item.durationMs
            if (duration > 0) position = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            delay(200)
        }
    }

    val updatedTap by rememberUpdatedState(onTap)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.aspectRatio(
                item.aspect.coerceAtLeast(0.2f),
                // Fit on both axes, the same rectangle photos use, so the flight to the grid lines up.
                matchHeightConstraintsFirst = item.aspect < 0.56f
            )
        ) {
            if (player != null) {
                AndroidView(
                    factory = { TextureView(it) },
                    update = { player.setVideoTextureView(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (!firstFrame) {
                val request = remember(item.id) {
                    ImageRequest.Builder(context).data(Thumb(item)).size(coil.size.Size.ORIGINAL).build()
                }
                AsyncImage(request, item.displayName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        // Gesture surface sits above the TextureView, which would otherwise swallow touches.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { updatedTap() }) }
                .mediaGestures(
                    zoomEnabled = false,
                    scale = { 1f },
                    onTransform = { _, _, _ -> },
                    onTransformEnd = {},
                    onVerticalDrag = onVerticalDrag,
                    onVerticalEnd = onVerticalEnd
                )
        )
        if (chromeVisible && player != null) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .bounceClick(pressedScale = 0.85f) { if (player.isPlaying) player.pause() else player.play() }
                    .clip(Aperture)
                    .background(Darkroom.Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pausar" else "Reproducir",
                    tint = Darkroom.OnAccent,
                    modifier = Modifier.size(36.dp)
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 136.dp, start = 20.dp, end = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .systemGestureExclusion()
                        .pointerInput(player) {
                            fun seek(x: Float) {
                                val f = (x / size.width).coerceIn(0f, 1f)
                                position = f
                                val duration = player.duration.takeIf { d -> d > 0 } ?: item.durationMs
                                player.seekTo((f * duration).toLong())
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                seek(down.position.x)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.first()
                                    seek(change.position.x)
                                    change.consume()
                                } while (change.pressed)
                            }
                        }
                ) {
                    val y = size.height / 2f
                    val x = size.width * position
                    drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(Darkroom.Accent, Offset(0f, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
                    drawCircle(Darkroom.Accent, 6.dp.toPx(), Offset(x, y))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(item.durationLabel, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}
