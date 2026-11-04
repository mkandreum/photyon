package com.example.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.data.MediaItem
import com.example.ui.components.SheetShape
import com.example.ui.components.SoftButton
import com.example.ui.theme.Darkroom
import com.example.ui.timeline.ThumbImage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val CELL = 40.dp
private val CELL_GAP = 3.dp

/**
 * Magnifying film strip: photos swell as they near the fixed center frame. Follows the pager and, while
 * dragged, drives it with one haptic tick per photo.
 */
@Composable
internal fun FilmStrip(items: List<MediaItem>, pager: PagerState, enabled: Boolean) {
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = pager.currentPage)
    val dragged by strip.interactionSource.collectIsDraggedAsState()
    val fling = rememberSnapFlingBehavior(strip, SnapPosition.Center)

    val centered by remember {
        derivedStateOf {
            val info = strip.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: 0
        }
    }

    // Strip -> pager, only while the user is the one moving the strip.
    var userDriving by remember { mutableStateOf(false) }
    LaunchedEffect(dragged, strip.isScrollInProgress) {
        if (dragged) userDriving = true else if (!strip.isScrollInProgress) userDriving = false
    }
    LaunchedEffect(Unit) {
        snapshotFlow { centered }.distinctUntilChanged().collectLatest { index ->
            if (userDriving && index != pager.currentPage && index < pager.pageCount) {
                pager.scrollToPage(index)
            }
        }
    }
    // Pager -> strip.
    LaunchedEffect(Unit) {
        snapshotFlow { pager.currentPage }.distinctUntilChanged().collectLatest { page ->
            if (!userDriving) strip.animateScrollToItem(page)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(66.dp),
        contentAlignment = Alignment.Center
    ) {
        val side = (maxWidth - CELL) / 2
        val cellPx = with(LocalDensity.current) { (CELL + CELL_GAP).toPx() }
        LazyRow(
            state = strip,
            userScrollEnabled = enabled,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, _ -> "strip" }) { index, item ->
                Box(
                    Modifier
                        .size(CELL, 52.dp)
                        .graphicsLayer {
                            // Read here, not in composition: the strip scrolls without recomposing.
                            val info = strip.layoutInfo
                            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val me = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val distance = if (me != null) abs(me.offset + me.size / 2f - mid) else Float.MAX_VALUE
                            val near = (1f - distance / (cellPx * 2.4f)).coerceIn(0f, 1f)
                            val s = 1f + 0.26f * near * near
                            scaleX = s
                            scaleY = s
                            alpha = 0.5f + 0.5f * near
                            shape = RoundedCornerShape(8.dp)
                            clip = true
                        }
                        .background(Darkroom.Raised)
                        .clickable(enabled = enabled) { userDriving = false }
                ) {
                    ThumbImage(item, 0, Modifier.fillMaxHeight())
                }
            }
        }
        // Fixed frame at the center: the strip passes beneath it.
        Box(
            Modifier
                .size(CELL + 10.dp, 66.dp)
                .border(2.dp, Darkroom.Accent, RoundedCornerShape(10.dp))
        )
    }
}

private val detailDate = DateTimeFormatter.ofPattern("d MMM yyyy", SPANISH)
private val detailTime = DateTimeFormatter.ofPattern("EEEE · HH:mm", SPANISH)

internal fun humanSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(Locale.US, bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1e6)
    else -> "${bytes / 1000} KB"
}

/** What rises from under the lifted photo: when, what, and the rest of that day. */
@Composable
internal fun DetailsPanel(
    item: MediaItem,
    sameDay: List<MediaItem>,
    onJump: (MediaItem) -> Unit,
    actions: ViewerActions,
    enabled: Boolean,
    heightFraction: Float,
    modifier: Modifier = Modifier
) {
    val when_ = remember(item.dateTakenMs) { Instant.ofEpochMilli(item.dateTakenMs).atZone(ZoneId.systemDefault()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(heightFraction)
            .clip(SheetShape)
            .background(Darkroom.Sheet)
            .then(if (enabled) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(when_.format(detailDate), style = MaterialTheme.typography.headlineMedium, color = Darkroom.Ink, maxLines = 1)
            Text(
                when_.format(detailTime).replaceFirstChar { it.titlecase(SPANISH) },
                style = MaterialTheme.typography.labelMedium,
                color = Darkroom.Muted
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.width > 0) {
                val mp = item.width * item.height / 1_000_000f
                Fact(if (item.isVideo) "${item.width} × ${item.height}" else "%.1f MP · %d × %d".format(Locale.US, mp, item.width, item.height))
            }
            Fact(humanSize(item.sizeBytes))
            if (item.isVideo) Fact(item.durationLabel)
            if (item.bucketName.isNotBlank()) Fact(item.bucketName)
            item.album?.let { Fact(it) }
        }
        Text(item.displayName, style = MaterialTheme.typography.labelSmall, color = Darkroom.Muted, maxLines = 2)

        if (sameDay.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ese mismo día", style = MaterialTheme.typography.titleSmall, color = Darkroom.Ink)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sameDay.size, key = { sameDay[it].id }) { i ->
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Darkroom.Raised)
                                .clickable(enabled = enabled) { onJump(sameDay[i]) }
                        ) { ThumbImage(sameDay[i], 0, Modifier.fillMaxWidth().fillMaxHeight()) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Álbum", { actions.onAlbum(item) }, Modifier.weight(1f), Icons.Rounded.CollectionsBookmark)
            SoftButton(
                if (item.isHidden) "Mostrar" else "Ocultar",
                { actions.onHideToggle(item) },
                Modifier.weight(1f),
                if (item.isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff
            )
        }
    }
}

@Composable
private fun Fact(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Darkroom.Ink,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Darkroom.Raised)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
