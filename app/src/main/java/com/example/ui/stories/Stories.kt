package com.example.ui.stories

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.example.data.MediaItem
import com.example.data.Story
import com.example.data.Thumb
import com.example.data.dateLabel
import com.example.ui.components.ApertureButton
import com.example.ui.components.bounceClick
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.Darkroom
import com.example.ui.timeline.ThumbImage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private const val SLIDE_MS = 5000
private val storyDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("es-ES"))

/** Stories need the real photo, not the grid thumbnail: the Ken Burns drift would blur it further. */
private const val STORY_PX = 2560

private fun storyRequest(context: android.content.Context, item: MediaItem): ImageRequest =
    ImageRequest.Builder(context)
        // Coil cannot decode video, so those keep the largest frame the system can render.
        .data(if (item.isVideo) Thumb(item, STORY_PX) else item.uri)
        .apply {
            if (item.isVideo) size(Size.ORIGINAL) else size(STORY_PX)
        }
        .precision(Precision.INEXACT)
        // The disk thumbnail paints immediately while the full image decodes.
        .placeholderMemoryCacheKey(Thumb(item).cacheKey)
        .crossfade(180)
        .build()

/** Row of portrait story cards with their dates; a lit frame means not watched yet today. */
@Composable
fun StoriesRow(
    stories: List<Story>,
    seen: Set<String>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    exclude: Set<String> = emptySet()
) {
    // The indices stay tied to the full story list: StoryPlayer always receives every story.
    val visible = remember(stories, exclude) { stories.indices.filter { stories[it].id !in exclude } }
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(visible, key = { stories[it].id }) { index ->
            val story = stories[index]
            val unseen = story.id !in seen
            val date = remember(story) { story.dateLabel() }
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .size(112.dp, 164.dp)
                    .bounceClick(pressedScale = 0.94f) { onOpen(index) }
                    .clip(shape)
                    .background(Darkroom.Raised)
            ) {
                ThumbImage(story.items.first(), 0, Modifier.fillMaxSize())
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to Color(0xE6000000)
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 8.dp, vertical = 9.dp)
                ) {
                    Text(
                        story.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        date,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp),
                        color = if (unseen) Darkroom.Accent else Color.White.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(if (unseen) 2.dp else 1.dp, if (unseen) Darkroom.Accent else Darkroom.Hairline, shape)
                )
            }
        }
    }
}

/**
 * Full-screen memories: each photo drifts slowly (Ken Burns) while its segment fills. Tap the sides to step,
 * hold to pause, swipe down to leave.
 */
@Composable
fun StoryPlayer(
    stories: List<Story>,
    startStory: Int,
    isFavorite: (MediaItem) -> Boolean,
    onFavorite: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onSeen: (Story) -> Unit,
    onClose: () -> Unit
) {
    var storyIndex by remember { mutableIntStateOf(startStory.coerceIn(0, stories.lastIndex)) }
    var slide by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val appear = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = rememberHaptics()

    val story = stories[storyIndex]
    val item = story.items[slide.coerceIn(0, story.items.lastIndex)]

    fun leave() {
        haptics.close()
        scope.launch {
            appear.animateTo(0f, tween(160))
            onClose()
        }
    }

    fun next() {
        when {
            slide < story.items.lastIndex -> {
                slide++
                haptics.tick()
            }
            storyIndex < stories.lastIndex -> {
                storyIndex++
                slide = 0
                haptics.threshold()
            }
            else -> leave()
        }
    }

    fun previous() {
        when {
            slide > 0 -> {
                slide--
                haptics.tick()
            }
            storyIndex > 0 -> {
                storyIndex--
                slide = 0
                haptics.threshold()
            }
            else -> scope.launch { progress.snapTo(0f) }
        }
    }

    BackHandler { leave() }
    LaunchedEffect(Unit) {
        haptics.open()
        appear.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 500f))
    }
    LaunchedEffect(story.id) { onSeen(story) }

    // Decode the next photo ahead of time so the change is instant.
    LaunchedEffect(storyIndex, slide) {
        val upcoming = story.items.getOrNull(slide + 1) ?: stories.getOrNull(storyIndex + 1)?.items?.firstOrNull()
        if (upcoming != null) {
            context.imageLoader.enqueue(storyRequest(context, upcoming))
        }
    }

    // Restarts per slide; pausing keeps the position.
    LaunchedEffect(storyIndex, slide) { progress.snapTo(0f) }
    val updatedNext by rememberUpdatedState(::next)
    LaunchedEffect(storyIndex, slide, paused) {
        if (!paused) {
            // Frame clock, not an animation: with system animations off, a tween would finish at once and
            // every story would race past.
            val start = progress.value
            val began = withFrameMillis { it }
            while (progress.value < 1f) {
                val now = withFrameMillis { it }
                progress.snapTo((start + (now - began).toFloat() / SLIDE_MS).coerceAtMost(1f))
            }
            updatedNext()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value * (1f - (abs(drag.value) / (size.height * 0.5f)).coerceIn(0f, 1f)) }
            .background(Color.Black)
            .graphicsLayer {
                translationY = drag.value
                val s = 0.94f + 0.06f * appear.value - (abs(drag.value) / size.height) * 0.15f
                scaleX = s
                scaleY = s
                clip = drag.value != 0f
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startedAt = down.uptimeMillis
                    paused = true
                    var total = Offset.Zero
                    var moved = false
                    var lastTime = startedAt
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        lastTime = change.uptimeMillis
                        total += change.positionChange()
                        if (!moved && abs(total.y) > slop) moved = true
                        if (moved) {
                            scope.launch { drag.snapTo((drag.value + change.positionChange().y).coerceAtLeast(0f)) }
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    paused = false
                    when {
                        moved -> scope.launch {
                            if (drag.value > size.height * 0.16f) leave()
                            else drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                        }
                        // A long hold only pauses; a quick tap steps.
                        lastTime - startedAt < 220 -> {
                            if (down.position.x < size.width * 0.33f) previous() else next()
                        }
                    }
                }
            }
    ) {
        val forward = (item.id.hashCode() and 1) == 0
        Crossfade(item, animationSpec = tween(260), label = "storySlide", modifier = Modifier.fillMaxSize()) { shown ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The slow drift; the outgoing slide just holds its last frame while fading.
                        val p = if (shown.id == item.id) progress.value else 1f
                        val s = if (forward) 1.04f + 0.1f * p else 1.14f - 0.1f * p
                        scaleX = s
                        scaleY = s
                        translationX = (if (forward) -1f else 1f) * (p - 0.5f) * 22.dp.toPx()
                    }
            ) {
                ThumbImage(shown, 0, Modifier.fillMaxSize())
                val context = LocalContext.current
                val request = remember(shown.id, shown.dateModifiedSec) { storyRequest(context, shown) }
                AsyncImage(
                    model = request,
                    contentDescription = shown.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // Keeps text readable over any photo.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xA6000000),
                        0.22f to Color.Transparent,
                        0.68f to Color.Transparent,
                        1f to Color(0xCC000000)
                    )
                )
        )
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .graphicsLayer { alpha = if (paused) 0.35f else 1f },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                story.items.indices.forEach { i ->
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                    ) {
                        val y = size.height / 2f
                        drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                        val fill = when {
                            i < slide -> 1f
                            i > slide -> 0f
                            else -> progress.value
                        }
                        if (fill > 0f) {
                            drawLine(Darkroom.Accent, Offset(0f, y), Offset(size.width * fill, y), size.height, StrokeCap.Round)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(story.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1)
                    Text(story.subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                }
                ApertureButton(Icons.Rounded.Close, "Cerrar", { leave() }, size = 44.dp)
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .graphicsLayer { alpha = if (paused) 0.35f else 1f },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (item.dateEstimated) "Sin fecha exacta"
                else Instant.ofEpochMilli(item.dateTakenMs).atZone(ZoneId.systemDefault()).format(storyDate),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            val favorite = isFavorite(item)
            Box(
                Modifier
                    .size(48.dp)
                    .bounceClick(pressedScale = 0.8f, feedback = { it.pop() }) { onFavorite(item) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (favorite) "Quitar de favoritas" else "Añadir a favoritas",
                    tint = if (favorite) Darkroom.Accent else Color.White
                )
            }
            Box(
                Modifier
                    .size(48.dp)
                    .bounceClick(pressedScale = 0.8f) { onShare(item) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.IosShare, "Compartir", tint = Color.White) }
        }
    }
}
