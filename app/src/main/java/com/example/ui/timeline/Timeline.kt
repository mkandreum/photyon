package com.example.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.example.data.MediaItem
import com.example.data.Thumb
import com.example.ui.components.PillShape
import com.example.ui.components.SelectionDot
import com.example.ui.components.floatingShadow
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.DayNumeralStyle
import com.example.ui.theme.Darkroom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Scroll position plus the geometry needed to find any photo on screen (opening animation, drag-select). */
@Stable
class TimelineState(val list: LazyListState) {
    var rows by mutableStateOf(TimelineRows.EMPTY)
    internal var rootX = 0f
    internal var rootY = 0f
    internal var pagePadPx = 0f

    private fun rowItem(rowIndex: Int) =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == rowIndex + HEADER_ITEMS }

    /** Where a photo currently is in window coordinates; null when off screen. */
    fun rectOf(id: String): Rect? {
        val rowIndex = rows.rowOfItem[id] ?: return null
        val photos = rows.rows.getOrNull(rowIndex) as? TimelineRow.Photos ?: return null
        val info = rowItem(rowIndex) ?: return null
        val slot = photos.items.indexOfFirst { it.id == id }
        if (slot < 0) return null
        val cell = photos.layout.cells[slot]
        val width = list.layoutInfo.viewportSize.width - pagePadPx * 2
        return Rect(
            left = rootX + pagePadPx + cell.x * width,
            top = rootY + info.offset + cell.y * width,
            right = rootX + pagePadPx + (cell.x + cell.w) * width,
            bottom = rootY + info.offset + (cell.y + cell.h) * width
        )
    }

    /** Brings a photo into view without animation, used when returning from the viewer. */
    suspend fun reveal(id: String) {
        if (rectOf(id) != null) return
        val rowIndex = rows.rowOfItem[id] ?: return
        list.scrollToItem(rowIndex + HEADER_ITEMS, -list.layoutInfo.viewportSize.height / 3)
    }

    internal fun itemAt(point: Offset): MediaItem? {
        val width = list.layoutInfo.viewportSize.width - pagePadPx * 2
        for (info in list.layoutInfo.visibleItemsInfo) {
            if (point.y < info.offset || point.y >= info.offset + info.size) continue
            val photos = rows.rows.getOrNull(info.index - HEADER_ITEMS) as? TimelineRow.Photos ?: return null
            val fx = (point.x - pagePadPx) / width
            val fy = (point.y - info.offset) / width
            photos.items.forEachIndexed { i, item ->
                val c = photos.layout.cells[i]
                if (fx >= c.x && fx < c.x + c.w && fy >= c.y && fy < c.y + c.h) return item
            }
            return null
        }
        return null
    }

    companion object {
        const val HEADER_ITEMS = 1
    }
}

@Composable
fun rememberTimelineState(): TimelineState {
    val list = rememberLazyListState()
    return remember(list) { TimelineState(list) }
}

/** The "Momentos" list: a mosaic by day that pinches into denser month and year overviews. */
@Composable
fun Timeline(
    items: List<MediaItem>,
    state: TimelineState,
    density: Density,
    onDensityChange: (Density) -> Unit,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 120.dp,
    header: @Composable () -> Unit = {}
) {
    val haptics = rememberHaptics()
    val currentDensity by rememberUpdatedState(density)
    val currentOnDensity by rememberUpdatedState(onDensityChange)

    val settle = remember { Animatable(1f) }
    var appliedDensity by remember { mutableStateOf(density) }

    LaunchedEffect(items, density) {
        val anchorId = if (appliedDensity != density) {
            state.list.layoutInfo.visibleItemsInfo
                .firstNotNullOfOrNull { state.rows.rows.getOrNull(it.index - TimelineState.HEADER_ITEMS) as? TimelineRow.Photos }
                ?.items?.first()?.id
        } else null
        val built = withContext(Dispatchers.Default) { Mosaic.build(items, density) }
        state.rows = built
        if (appliedDensity != density) {
            appliedDensity = density
            anchorId?.let { id -> built.rowOfItem[id]?.let { state.list.scrollToItem(it + TimelineState.HEADER_ITEMS) } }
            settle.snapTo(0.94f)
            settle.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 500f))
        }
    }

    val selectionMode = selected.isNotEmpty()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelection by rememberUpdatedState(onSelectionChange)
    val rows = state.rows
    val pagePad = 6.dp
    val pagePadPx = with(LocalDensity.current) { pagePad.toPx() }
    state.pagePadPx = pagePadPx

    var autoScroll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoScroll != 0f) {
        while (isActive && autoScroll != 0f) {
            state.list.scrollBy(autoScroll)
            delay(16)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { c ->
                val b = c.boundsInRoot()
                state.rootX = b.left
                state.rootY = b.top
            }
            // Two fingers change density at any time; one finger is left alone for scrolling.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var zoom = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            val next = when {
                                zoom > 1.22f -> currentDensity.looser
                                zoom < 0.82f -> currentDensity.tighter
                                else -> currentDensity
                            }
                            if (next != currentDensity) {
                                haptics.tick()
                                currentOnDensity(next)
                                zoom = 1f
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // Long press selects, keep dragging to sweep a range; edges auto-scroll.
            .pointerInput(Unit) {
                var anchor = -1
                var base: Set<String> = emptySet()
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        state.itemAt(offset)?.let { item ->
                            haptics.longPress()
                            anchor = state.rows.positionOfItem[item.id] ?: -1
                            base = currentSelected
                            currentOnSelection(base + item.id)
                        }
                    },
                    onDragEnd = { anchor = -1; autoScroll = 0f },
                    onDragCancel = { anchor = -1; autoScroll = 0f },
                    onDrag = { change, _ ->
                        change.consume()
                        if (anchor >= 0) {
                            val h = size.height.toFloat()
                            val edge = 80.dp.toPx()
                            autoScroll = when {
                                change.position.y < edge -> -18f
                                change.position.y > h - edge -> 18f
                                else -> 0f
                            }
                            state.itemAt(change.position)?.let { item ->
                                val at = state.rows.positionOfItem[item.id] ?: return@let
                                val range = state.rows.items.subList(minOf(anchor, at), maxOf(anchor, at) + 1)
                                val next = base + range.map { it.id }
                                if (next != currentSelected) {
                                    haptics.tick()
                                    currentOnSelection(next)
                                }
                            }
                        }
                    }
                )
            }
            .graphicsLayer {
                scaleX = settle.value
                scaleY = settle.value
                alpha = 0.4f + 0.6f * ((settle.value - 0.94f) / 0.06f).coerceIn(0f, 1f)
            }
    ) {
        LazyColumn(
            state = state.list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            item(key = "hero", contentType = "hero") { header() }
            items(
                count = rows.rows.size,
                key = { rows.rows[it].key },
                contentType = {
                    when (val r = rows.rows[it]) {
                        is TimelineRow.Header -> "header"
                        is TimelineRow.Photos -> r.layout.name
                    }
                }
            ) { index ->
                when (val row = rows.rows[index]) {
                    is TimelineRow.Header -> HeaderRow(row)
                    is TimelineRow.Photos -> PhotoRow(
                        row = row,
                        pagePad = pagePad,
                        dense = density == Density.Years,
                        selectionMode = selectionMode,
                        selected = selected,
                        parallax = {
                            // -1 at the bottom of the screen, 1 at the top.
                            val info = state.list.layoutInfo
                            val me = info.visibleItemsInfo.firstOrNull { it.index == index + TimelineState.HEADER_ITEMS }
                            if (me == null || info.viewportSize.height == 0) 0f
                            else (1f - (me.offset + me.size / 2f) / info.viewportSize.height * 2f).coerceIn(-1f, 1f)
                        },
                        onTap = { item ->
                            if (selectionMode) {
                                haptics.tick()
                                onSelectionChange(if (item.id in selected) selected - item.id else selected + item.id)
                            } else onOpen(item)
                        }
                    )
                }
            }
        }
        TimeDial(state = state, modifier = Modifier.align(Alignment.CenterEnd))
        DatePill(state, Modifier.align(Alignment.TopCenter))
    }
}

/** While scrolling, a pill floats at the top saying which day and place you are passing through. */
@Composable
private fun DatePill(state: TimelineState, modifier: Modifier = Modifier) {
    val scrolling by remember { derivedStateOf { state.list.isScrollInProgress && state.list.firstVisibleItemIndex > 1 } }
    val item by remember {
        derivedStateOf {
            val rows = state.rows.rows
            var i = (state.list.firstVisibleItemIndex - TimelineState.HEADER_ITEMS).coerceAtLeast(0)
            while (i < rows.size && rows[i] !is TimelineRow.Photos) i++
            (rows.getOrNull(i) as? TimelineRow.Photos)?.items?.first()
        }
    }
    var lingering by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) lingering = true else {
            delay(700)
            lingering = false
        }
    }
    val label = item?.let { current -> remember(current.id, current.place) { Mosaic.pillLabel(current) } }
    AnimatedVisibility(
        visible = lingering && label != null,
        enter = fadeIn(tween(140)) + slideInVertically { -it / 2 },
        exit = fadeOut(tween(220)) + slideOutVertically { -it / 2 },
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 6.dp)
    ) {
        Text(
            label.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = Darkroom.OnAccent,
            maxLines = 1,
            modifier = Modifier
                .floatingShadow(PillShape, 10.dp)
                .clip(PillShape)
                .background(Darkroom.Accent)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun HeaderRow(header: TimelineRow.Header) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = if (header.big) 26.dp else 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (header.numeral.isNotEmpty()) {
            Text(header.numeral, style = DayNumeralStyle, color = Darkroom.Ink, maxLines = 1)
        }
        Column {
            if (header.title.isNotEmpty()) {
                Text(
                    header.title,
                    style = if (header.big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    color = Darkroom.Ink,
                    maxLines = 1
                )
            }
            Text(header.subtitle, style = MaterialTheme.typography.labelMedium, color = Darkroom.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun PhotoRow(
    row: TimelineRow.Photos,
    pagePad: Dp,
    dense: Boolean,
    selectionMode: Boolean,
    selected: Set<String>,
    parallax: () -> Float,
    onTap: (MediaItem) -> Unit
) {
    val layout = row.layout
    val gap = if (dense) 1.dp else 2.dp
    val corner = remember(dense) { RoundedCornerShape(if (dense) 0.dp else 8.dp) }
    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pagePad),
        content = {
            row.items.forEachIndexed { i, item ->
                Tile(
                    item = item,
                    widthFraction = layout.cells[i].w,
                    gap = gap,
                    shape = corner,
                    compactBadges = dense,
                    selectionMode = selectionMode,
                    isSelected = item.id in selected,
                    parallax = parallax,
                    onTap = onTap
                )
            }
        }
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val placeables = measurables.mapIndexed { i, m ->
            val c = layout.cells[i]
            val cw = (c.w * w).toInt()
            val ch = (c.h * w).toInt()
            m.measure(Constraints.fixed(cw, ch))
        }
        layout(w, (layout.heightRatio * w).toInt()) {
            placeables.forEachIndexed { i, p ->
                val c = layout.cells[i]
                p.place((c.x * w).toInt(), (c.y * w).toInt())
            }
        }
    }
}

@Composable
private fun Tile(
    item: MediaItem,
    widthFraction: Float,
    gap: Dp,
    shape: RoundedCornerShape,
    compactBadges: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    parallax: (() -> Float)?,
    onTap: (MediaItem) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(gap)
            .clip(shape)
            .background(Darkroom.Raised)
            .clickable(onClickLabel = "Abrir") { onTap(item) }
    ) {
        val inset = if (isSelected) 0.88f else 1f
        val wide = widthFraction > 0.9f && parallax != null
        val imageModifier = Modifier
            .fillMaxSize()
            .then(
                when {
                    isSelected -> Modifier.graphicsLayer { scaleX = inset; scaleY = inset }
                    // Full-width photos drift against the scroll, read in the draw phase only.
                    wide -> Modifier.graphicsLayer {
                        scaleX = 1.14f
                        scaleY = 1.14f
                        translationY = parallax!!() * size.height * 0.1f
                    }
                    else -> Modifier
                }
            )
        ThumbImage(item, 0, imageModifier)
        // Large mosaic tiles sharpen on top of the small thumbnail as soon as the bigger render arrives.
        if (widthFraction >= 0.5f) ThumbImage(item, if (widthFraction > 0.9f) 1280 else 768, imageModifier)

        if (item.isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = if (compactBadges) 2.dp else 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlayArrow, "Vídeo", tint = Color.White, modifier = Modifier.size(14.dp))
                if (!compactBadges) {
                    Text(item.durationLabel, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
                }
            }
        }
        if (item.isFavorite && !compactBadges) {
            Icon(
                Icons.Rounded.Favorite, "Favorita", tint = Darkroom.Accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(16.dp)
            )
        }
        if (selectionMode) {
            SelectionDot(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp), if (compactBadges) 16.dp else 24.dp)
        }
    }
}

/** Crop-filled tile image. No crossfade and no size lookup, so memory-cache hits draw on the first frame. */
@Composable
fun ThumbImage(item: MediaItem, px: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(item.id, item.dateModifiedSec, px) {
        ImageRequest.Builder(context)
            .data(Thumb(item, px))
            .size(Size.ORIGINAL)
            // Only applies when loading from disk: cached tiles still draw on the first frame.
            .crossfade(if (px == 0) 110 else 180)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = item.displayName,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}
