package com.example.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Animatable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.data.MediaItem
import com.example.data.Thumbnails
import com.example.ui.components.ApertureButton
import com.example.ui.components.PillShape
import com.example.ui.components.bounceClick
import com.example.ui.components.floatingShadow
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.Darkroom
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

internal val SPANISH = Locale.forLanguageTag("es-ES")

class ViewerActions(
    val onShare: (MediaItem) -> Unit,
    val onFavorite: (MediaItem) -> Unit,
    val onTrash: (MediaItem) -> Unit,
    val onHideToggle: (MediaItem) -> Unit,
    val onAlbum: (MediaItem) -> Unit,
    val onSaveAdjustments: (MediaItem, Adjustments) -> Unit
)

/**
 * "La mesa": the photo lies on a table lit by its own color. Swipe sideways or scrub the magnifying film
 * strip to move; swipe up and the photo lifts into a card to read its details; swipe down to put it back.
 */
@Composable
fun Viewer(
    items: List<MediaItem>,
    startId: String,
    originOf: (String) -> Rect?,
    onRevealInGrid: suspend (String) -> Unit,
    actions: ViewerActions,
    onClose: () -> Unit
) {
    val startIndex = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val context = LocalContext.current

    val open = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    val dismiss = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(originOf(startId)) }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Adjustments?>(null) }

    val current = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex))
    val revealed by remember { derivedStateOf { reveal.value > 0.5f } }

    // Immersive while chrome is hidden; always restored on exit.
    val window = (context as? Activity)?.window
    LaunchedEffect(chrome) {
        window?.let {
            val controller = WindowCompat.getInsetsController(it, it.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    LaunchedEffect(Unit) { open.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = 420f)) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            val id = items.getOrNull(pager.currentPage)?.id
            if (id != null) {
                onRevealInGrid(id)
                origin = originOf(id)
            }
            coroutineScope {
                launch { reveal.animateTo(0f, tween(160)) }
                launch { dismiss.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f)) }
                launch { open.animateTo(0f, spring(dampingRatio = 1f, stiffness = 520f)) }
            }
            onClose()
        }
    }

    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() }

    BackHandler {
        when {
            revealed -> scope.launch { reveal.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
            else -> close()
        }
    }

    // Ambient light: the photo's own average color, darkened, behind everything.
    val ambient = remember { Animatable(Color.Black) }
    LaunchedEffect(pager, items) {
        snapshotFlow { pager.settledPage }.collectLatest { page ->
            val item = items.getOrNull(page) ?: return@collectLatest
            val argb = Thumbnails.ambientColor(context, item)
            val target = if (argb != null) lerp(Color(argb), Color.Black, 0.55f) else Color.Black
            ambient.animateTo(target, tween(420))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val panelHeightPx = hPx * 0.58f

        // Backdrop
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (open.value * (1f - abs(dismiss.value) / (hPx * 0.6f))).coerceIn(0f, 1f)
                }
                .background(Color.Black)
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            0f to ambient.value,
                            1f to Color.Black,
                            center = Offset(size.width / 2f, size.height * 0.42f),
                            radius = size.maxDimension * 0.72f
                        )
                    )
                }
        )

        // The photo on the table
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed && !revealed,
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val o = open.value
                    val r = reveal.value
                    val dragged = abs(dismiss.value) / hPx
                    val rect = origin
                    // Fly from (and back to) the tile the photo lives in.
                    val fromScale = if (rect != null) (rect.width / wPx).coerceIn(0.1f, 1f) else 0.86f
                    val flyScale = fromScale + (1f - fromScale) * o
                    val flyX = if (rect != null) (rect.center.x - wPx / 2f) * (1f - o) else 0f
                    val flyY = if (rect != null) (rect.center.y - hPx / 2f) * (1f - o) else 0f

                    val liftScale = 1f - 0.46f * r
                    val s = flyScale * liftScale * (1f - 0.22f * min(1f, dragged * 2f))
                    scaleX = s
                    scaleY = s
                    translationX = flyX
                    translationY = flyY + dismiss.value - r * hPx * 0.26f
                    transformOrigin = TransformOrigin.Center
                    alpha = if (rect == null) o.coerceIn(0f, 1f) else 1f
                    shape = RoundedCornerShape((28.dp.toPx() * maxOf(r, 1f - o, min(1f, dragged * 6f))))
                    clip = r > 0f || o < 1f || dismiss.value != 0f
                }
        ) { page ->
            val item = items[page]
            val isCurrent = pager.settledPage == page
            ViewerPage(
                item = item,
                preview = preview.takeIf { isCurrent && adjusting },
                active = isCurrent && !revealed && !closing && open.value > 0.9f,
                chromeVisible = chrome,
                onZoomChanged = { if (isCurrent) zoomed = it },
                onTap = {
                    if (revealed) scope.launch { reveal.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 420f)) }
                    else chrome = !chrome
                },
                onVerticalDrag = { dy ->
                    scope.launch {
                        if (reveal.value > 0f || (dy < 0f && dismiss.value == 0f)) {
                            reveal.snapTo((reveal.value - dy / panelHeightPx).coerceIn(0f, 1f))
                        } else {
                            dismiss.snapTo(dismiss.value + dy)
                        }
                    }
                },
                onVerticalEnd = { velocity ->
                    scope.launch {
                        when {
                            dismiss.value != 0f -> {
                                if (dismiss.value > hPx * 0.14f || velocity > 1400f) close()
                                else dismiss.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f))
                            }
                            reveal.value > 0f -> {
                                val target = if (velocity < -600f || (velocity < 600f && reveal.value > 0.35f)) 1f else 0f
                                if (target == 1f && reveal.value < 1f) haptics.tick()
                                reveal.animateTo(target, spring(dampingRatio = 0.84f, stiffness = 380f))
                            }
                        }
                    }
                }
            )
        }

        // Fades away as the photo lifts or is dragged.
        val chromeAlpha by animateFloatAsState(if (chrome && !zoomed) 1f else 0f, tween(180), label = "chrome")
        fun chromeLayer(): Float = chromeAlpha * (1f - reveal.value) * open.value * (1f - min(1f, abs(dismiss.value) / (hPx * 0.1f)))

        if (current != null) {
            // Top bar
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .graphicsLayer { alpha = chromeLayer() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ApertureButton(Icons.Rounded.ArrowBackIosNew, "Volver", onClick = ::close, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        dateTitle(current),
                        style = MaterialTheme.typography.titleMedium,
                        color = Darkroom.Ink,
                        maxLines = 1
                    )
                    Text(
                        "${pager.currentPage + 1} de ${items.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Darkroom.Muted,
                        maxLines = 1
                    )
                }
            }

            // Film strip + actions
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
                    .graphicsLayer { alpha = chromeLayer() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilmStrip(items = items, pager = pager, enabled = chrome && !revealed)
                ActionPill {
                    PillAction(Icons.Rounded.IosShare, "Compartir") { actions.onShare(current) }
                    FavoriteAction(current.isFavorite) { actions.onFavorite(current) }
                    PillAction(Icons.Rounded.Tune, "Ajustar") { adjusting = true }
                    PillAction(Icons.Outlined.Info, "Detalles") {
                        scope.launch { reveal.animateTo(1f, spring(dampingRatio = 0.84f, stiffness = 380f)) }
                    }
                    PillAction(Icons.Rounded.DeleteOutline, "Eliminar", tint = Darkroom.Danger) { actions.onTrash(current) }
                }
            }

            // Details rise from under the lifted photo
            DetailsPanel(
                item = current,
                sameDay = remember(items, current.id) { sameDayOf(items, current) },
                onJump = { target ->
                    val idx = items.indexOfFirst { it.id == target.id }
                    if (idx >= 0) scope.launch {
                        pager.scrollToPage(idx)
                    }
                },
                actions = actions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        alpha = reveal.value
                        translationY = (1f - reveal.value) * panelHeightPx * 0.4f
                    },
                enabled = revealed,
                heightFraction = 0.58f
            )
        }
    }

    if (adjusting && current != null) {
        AdjustSheet(
            item = current,
            onChange = { preview = it },
            onDismiss = { adjusting = false; preview = null },
            onSave = {
                actions.onSaveAdjustments(current, it)
                adjusting = false
                preview = null
            }
        )
    }
}

internal fun sameDayOf(items: List<MediaItem>, item: MediaItem): List<MediaItem> {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(item.dateTakenMs).atZone(zone).toLocalDate()
    return items.asSequence()
        .filter { it.id != item.id && Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate() == day }
        .take(24)
        .toList()
}

private val titleFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm", SPANISH)

private fun dateTitle(item: MediaItem): String =
    Instant.ofEpochMilli(item.dateTakenMs).atZone(ZoneId.systemDefault()).format(titleFormat)
        .replaceFirstChar { it.titlecase(SPANISH) }

@Composable
private fun ActionPill(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .floatingShadow(PillShape, 20.dp)
            .clip(PillShape)
            .background(Darkroom.Nav)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun PillAction(icon: ImageVector, label: String, tint: Color = Darkroom.Ink, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .bounceClick(pressedScale = 0.84f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun FavoriteAction(favorite: Boolean, onClick: () -> Unit) {
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(favorite) }
    LaunchedEffect(favorite) {
        if (favorite != seen) {
            seen = favorite
            pop.snapTo(0.5f)
            pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMedium))
        }
    }
    Box(
        modifier = Modifier
            .size(52.dp)
            .bounceClick(pressedScale = 0.84f, feedback = { it.confirm() }, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favorite) "Quitar de favoritos" else "Añadir a favoritos",
            tint = if (favorite) Darkroom.Accent else Darkroom.Ink,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
        )
    }
}
