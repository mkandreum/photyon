package com.example.ui.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.MediaItem
import com.example.data.Thumbnails
import com.example.ui.components.Aperture
import com.example.ui.components.ApertureButton
import com.example.ui.components.ChoiceChip
import com.example.ui.components.PillShape
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SoftButton
import com.example.ui.components.bounceClick
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.Darkroom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val RATIOS = listOf<Pair<String, Float?>>(
    "Libre" to null, "1:1" to 1f, "4:5" to 0.8f, "3:2" to 1.5f, "16:9" to 16f / 9f, "9:16" to 9f / 16f
)

private fun Float.editLabel(): String = "%+d".format(((this) * 100).toInt())

/** Opens the right editor. [onSaved] runs after the new file exists; the original stays untouched. */
@Composable
fun Editor(item: MediaItem, onClose: () -> Unit, onSaved: () -> Unit, onError: (String) -> Unit) {
    if (item.isVideo) VideoEditor(item, onClose, onSaved, onError) else PhotoEditor(item, onClose, onSaved, onError)
}

@Composable
private fun EditorFrame(
    title: String,
    dirty: Boolean,
    saving: Boolean,
    progress: Float?,
    onClose: () -> Unit,
    onSave: () -> Unit,
    preview: @Composable () -> Unit,
    controls: @Composable () -> Unit
) {
    var confirm by remember { mutableStateOf(false) }
    val leave = { if (dirty && !saving) confirm = true else if (!saving) onClose() }
    BackHandler { leave() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Darkroom.Canvas)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ApertureButton(Icons.Rounded.Close, "Cerrar el editor", { leave() }, size = 44.dp)
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = Darkroom.Muted, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .height(44.dp)
                    .bounceClick(enabled = dirty && !saving, onClick = onSave)
                    .clip(PillShape)
                    .background(if (dirty) Darkroom.Accent else Darkroom.Chip)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Guardar copia",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (dirty) Darkroom.OnAccent else Darkroom.Muted
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) { preview() }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) { controls() }
    }
    if (saving) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress },
                        color = Darkroom.Accent,
                        trackColor = Darkroom.Hairline
                    )
                    Text("EXPORTANDO ${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Darkroom.Ink)
                } else {
                    CircularProgressIndicator(color = Darkroom.Accent, trackColor = Darkroom.Hairline)
                    Text("REVELANDO…", style = MaterialTheme.typography.labelMedium, color = Darkroom.Ink)
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Darkroom.Raised,
            title = { Text("¿Descartar los cambios?", style = MaterialTheme.typography.titleLarge, color = Darkroom.Ink) },
            text = { Text("La edición no se ha guardado.", style = MaterialTheme.typography.bodyMedium, color = Darkroom.Muted) },
            confirmButton = {
                TextButton(onClick = onClose) { Text("Descartar", color = Darkroom.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Seguir editando", color = Darkroom.Ink) }
            }
        )
    }
}

@Composable
private fun TabRow(tabs: List<String>, current: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tabs, key = { it }) { ChoiceChip(it, it == current, { onSelect(it) }) }
    }
}

@Composable
private fun EditSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    neutral: Float = 0f,
    onValue: (Float) -> Unit
) {
    val haptics = rememberHaptics()
    // A small detent at "no change", felt under the finger.
    val magnet = (range.endInclusive - range.start) * 0.02f
    Column(Modifier.padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Darkroom.Muted)
            Text(display, style = MaterialTheme.typography.labelSmall, color = Darkroom.Ink)
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                val snapped = if (abs(raw - neutral) < magnet) neutral else raw
                if (snapped == neutral && value != neutral) haptics.tick()
                onValue(snapped)
            },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Darkroom.Accent,
                activeTrackColor = Darkroom.Accent,
                inactiveTrackColor = Darkroom.Hairline
            )
        )
    }
}

/* ---- Photo ---- */

@Composable
private fun PhotoEditor(item: MediaItem, onClose: () -> Unit, onSaved: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val base by produceState<Bitmap?>(null, item.id) {
        // The thumbnail appears at once and is replaced as soon as the sharper version is decoded.
        value = withContext(Dispatchers.IO) { Thumbnails.smallBitmap(context, item) }
        val sharp = withContext(Dispatchers.IO) {
            runCatching { EditorStore.decode(context, item.uri, 1400) }.getOrNull()
        }
        if (sharp != null) value = sharp
        if (value == null) {
            onError("No se puede editar este archivo")
            onClose()
        }
    }
    var tab by remember { mutableStateOf("Carretes") }
    var turns by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(CropRect()) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var values by remember { mutableStateOf(EditValues()) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val shown = remember(base, turns, flipped) { base?.let { EditorStore.transformed(it, turns, flipped) } }
    val small = remember(base) {
        base?.let {
            val s = 160f / max(it.width, it.height)
            Bitmap.createScaledBitmap(it, (it.width * s).toInt().coerceAtLeast(1), (it.height * s).toInt().coerceAtLeast(1), true)
        }
    }
    val filter = remember(values, comparing) {
        if (comparing || values.isOriginal) null else ColorFilter.colorMatrix(ColorMatrix(ColorMath.of(values)))
    }
    val dirty = !values.isOriginal || !crop.isFull || turns % 4 != 0 || flipped

    EditorFrame(
        title = "Revelado",
        dirty = dirty,
        saving = saving,
        progress = null,
        onClose = onClose,
        onSave = {
            saving = true
            scope.launch {
                runCatching { EditorStore.savePhoto(context, item, turns, flipped, crop, values) }
                    .onSuccess { onSaved() }
                    .onFailure {
                        saving = false
                        onError("No se pudo guardar la copia")
                    }
            }
        },
        preview = {
            val bitmap = shown
            if (bitmap == null) {
                CircularProgressIndicator(color = Darkroom.Accent, trackColor = Darkroom.Hairline)
            } else {
                val aspect = bitmap.width.toFloat() / bitmap.height
                Box(
                    Modifier
                        .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 0.7f)
                        .then(
                            if (tab != "Recortar") {
                                // Hold to compare with the original.
                                Modifier.pointerInput(Unit) {
                                    detectTapGestures(onPress = {
                                        comparing = true
                                        tryAwaitRelease()
                                        comparing = false
                                    })
                                }
                            } else Modifier
                        )
                ) {
                    Image(
                        bitmap.asImageBitmap(),
                        item.displayName,
                        contentScale = ContentScale.FillBounds,
                        colorFilter = filter,
                        modifier = Modifier
                            .fillMaxSize()
                            .filmGrain(if (comparing) 0f else values.filter.grain)
                    )
                    if (tab == "Recortar") {
                        CropOverlay(crop, ratio, aspect) { crop = it }
                    }
                }
            }
        },
        controls = {
            Box(Modifier.height(148.dp)) {
                when (tab) {
                    "Carretes" -> LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(PhotoFilter.entries.toList(), key = { it.name }) { f ->
                            FilmRoll(f, small, f == values.filter) { values = values.copy(filter = f) }
                        }
                    }
                    "Luz" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        EditSlider("Brillo", values.brightness, -0.4f..0.4f, values.brightness.editLabel()) { values = values.copy(brightness = it) }
                        EditSlider("Contraste", values.contrast, 0.6f..1.6f, (values.contrast - 1f).editLabel(), neutral = 1f) { values = values.copy(contrast = it) }
                    }
                    "Color" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        EditSlider("Saturación", values.saturation, 0f..2f, (values.saturation - 1f).editLabel(), neutral = 1f) { values = values.copy(saturation = it) }
                        EditSlider("Temperatura", values.warmth, -0.3f..0.3f, values.warmth.editLabel()) { values = values.copy(warmth = it) }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                        LazyRow(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(RATIOS, key = { it.first }) { (label, r) ->
                                ChoiceChip(label, r == ratio, {
                                    ratio = r
                                    shown?.let { crop = CropRect.centered(r, it.width.toFloat() / it.height) }
                                })
                            }
                        }
                        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton("Girar", {
                                turns = (turns + 1) % 4
                                crop = CropRect()
                                ratio = null
                            }, Modifier.weight(1f), Icons.Rounded.RotateRight)
                            SoftButton("Voltear", {
                                flipped = !flipped
                                crop = CropRect()
                            }, Modifier.weight(1f), Icons.Rounded.Flip)
                        }
                    }
                }
            }
            TabRow(listOf("Carretes", "Luz", "Color", "Recortar"), tab) { tab = it }
        }
    )
}

/** A 35 mm roll: the cassette with its brand on the label, and the leader showing the photo through that film. */
@Composable
private fun FilmRoll(filter: PhotoFilter, small: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    val color = remember(filter) {
        if (filter == PhotoFilter.Original) null else ColorFilter.colorMatrix(ColorMatrix(filter.matrix()))
    }
    val casing = Color(filter.casing)
    val band = Color(filter.band)
    val onCasing = Color(filter.onCasing)
    val lift by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 400f),
        label = "rollLift"
    )
    Column(
        Modifier
            .width(118.dp)
            .bounceClick(pressedScale = 0.93f, feedback = { it.tick() }, onClick = onClick)
            .graphicsLayer { translationY = -6.dp.toPx() * lift }
            .semantics(mergeDescendants = true) {
                contentDescription = "Filtro ${filter.brand} ${filter.label} ${filter.iso}"
                this.selected = selected
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.height(100.dp), verticalAlignment = Alignment.CenterVertically) {
            // Cassette
            Box(Modifier.size(44.dp, 100.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val knobW = size.width * 0.42f
                    val knobX = (size.width - knobW) / 2f
                    drawRoundRect(Color(0xFF3A3532), Offset(knobX, 0f), Size(knobW, 9.dp.toPx()), CornerRadius(2.dp.toPx()))
                    val top = 7.dp.toPx()
                    val bodyH = size.height - top * 2
                    drawRoundRect(casing, Offset(0f, top), Size(size.width, bodyH), CornerRadius(6.dp.toPx()))
                    drawRect(band, Offset(0f, top + bodyH * 0.72f), Size(size.width, bodyH * 0.12f))
                    // Metal caps
                    val cap = Color(0xFFB9B4AE)
                    drawRect(cap, Offset(0f, top), Size(size.width, 3.dp.toPx()))
                    drawRect(cap, Offset(0f, top + bodyH - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
                }
                Text(
                    filter.brand,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
                    color = onCasing,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .graphicsLayer {
                            rotationZ = -90f
                            translationY = -6.dp.toPx()
                        }
                        .wrapContentSize(unbounded = true)
                )
            }
            // Leader
            Box(
                Modifier
                    .size(70.dp, 80.dp)
                    .background(Color(0xFF14110F))
                    .then(if (selected) Modifier.border(2.dp, Darkroom.Accent) else Modifier)
            ) {
                if (small != null) {
                    Image(
                        small.asImageBitmap(), filter.label,
                        contentScale = ContentScale.Crop, colorFilter = color,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 10.dp, horizontal = 2.dp)
                            .filmGrain(filter.grain)
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    val hole = Size(5.dp.toPx(), 5.dp.toPx())
                    val y2 = size.height - hole.height - 2.5.dp.toPx()
                    var x = 3.dp.toPx()
                    while (x < size.width - hole.width) {
                        drawRoundRect(Color(0xFFD9D4CE), Offset(x, 2.5.dp.toPx()), hole, CornerRadius(1.dp.toPx()))
                        drawRoundRect(Color(0xFFD9D4CE), Offset(x, y2), hole, CornerRadius(1.dp.toPx()))
                        x += hole.width + 4.dp.toPx()
                    }
                }
            }
        }
        Text(
            if (filter == PhotoFilter.Original) "ORIGINAL" else "${filter.label} ${filter.iso}".uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.2.sp),
            color = if (selected) Darkroom.Accent else Darkroom.Muted,
            maxLines = 1
        )
    }
}

/** Drag a corner to resize, inside to move. Everything outside the crop dims. */
@Composable
private fun CropOverlay(crop: CropRect, ratio: Float?, aspect: Float, onChange: (CropRect) -> Unit) {
    val haptics = rememberHaptics()
    val touch = with(LocalDensity.current) { 40.dp.toPx() }
    val current = androidx.compose.runtime.rememberUpdatedState(crop)
    val update = androidx.compose.runtime.rememberUpdatedState(onChange)
    val lockedRatio = androidx.compose.runtime.rememberUpdatedState(ratio)
    Canvas(
        Modifier
            .fillMaxSize()
            .systemGestureExclusion()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(aspect) {
                var cornerX = -1
                var cornerY = -1
                detectDragGestures(
                    onDragStart = { p ->
                        val c = current.value
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val nearLeft = abs(p.x - c.left * w) < touch
                        val nearRight = abs(p.x - c.right * w) < touch
                        val nearTop = abs(p.y - c.top * h) < touch
                        val nearBottom = abs(p.y - c.bottom * h) < touch
                        cornerX = if (nearLeft) 0 else if (nearRight) 1 else -1
                        cornerY = if (nearTop) 0 else if (nearBottom) 1 else -1
                        if (cornerX < 0 || cornerY < 0) {
                            cornerX = -1
                            cornerY = -1
                        } else haptics.tick()
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val c = current.value
                        update.value(
                            if (cornerX < 0) c.moved(drag.x / w, drag.y / h)
                            else CropRect.resized(
                                c, cornerX, cornerY,
                                (change.position.x / w).coerceIn(0f, 1f),
                                (change.position.y / h).coerceIn(0f, 1f),
                                lockedRatio.value, aspect
                            )
                        )
                    }
                )
            }
    ) {
        val l = crop.left * size.width
        val t = crop.top * size.height
        val w = crop.width * size.width
        val h = crop.height * size.height
        drawRect(Color(0xB3000000))
        drawRect(Color.Black, Offset(l, t), Size(w, h), blendMode = BlendMode.Clear)
        val grid = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            drawLine(grid, Offset(l + w * i / 3f, t), Offset(l + w * i / 3f, t + h), 1f)
            drawLine(grid, Offset(l, t + h * i / 3f), Offset(l + w, t + h * i / 3f), 1f)
        }
        drawRect(Darkroom.Accent, Offset(l, t), Size(w, h), style = Stroke(1.5.dp.toPx()))
        val arm = min(24.dp.toPx(), min(w, h) / 2f)
        val thick = 3.dp.toPx()
        listOf(Offset(l, t) to Offset(1f, 1f), Offset(l + w, t) to Offset(-1f, 1f), Offset(l, t + h) to Offset(1f, -1f), Offset(l + w, t + h) to Offset(-1f, -1f))
            .forEach { (p, d) ->
                drawLine(Darkroom.Accent, p, p + Offset(arm * d.x, 0f), thick)
                drawLine(Darkroom.Accent, p, p + Offset(0f, arm * d.y), thick)
            }
    }
}

/* ---- Video ---- */

private val SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)

private fun timeLabel(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoEditor(item: MediaItem, onClose: () -> Unit, onSaved: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val duration = item.durationMs.coerceAtLeast(1L)
    var tab by remember { mutableStateOf("Recortar") }
    var start by remember { mutableFloatStateOf(0f) }
    var end by remember { mutableFloatStateOf(1f) }
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var turns by remember { mutableIntStateOf(0) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var filter by remember { mutableStateOf(PhotoFilter.Original) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var head by remember { mutableFloatStateOf(0f) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    LaunchedEffect(speed) { player.playbackParameters = PlaybackParameters(speed) }
    LaunchedEffect(brightness, contrast, saturation, filter) {
        // Debounced: rebuilding the effect pipeline on every slider tick would stutter.
        delay(120)
        runCatching { player.setVideoEffects(EditorStore.colorEffects(brightness, contrast, saturation, filter)) }
    }
    // Plays only the kept range.
    LaunchedEffect(player) {
        while (true) {
            val pos = player.currentPosition
            head = (pos.toFloat() / duration).coerceIn(0f, 1f)
            if (pos > end * duration || pos < start * duration - 200) player.seekTo((start * duration).toLong())
            delay(60)
        }
    }

    val frames = remember { mutableStateListOf<Bitmap>() }
    LaunchedEffect(item.id) {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            runCatching {
                retriever.setDataSource(context, item.uri)
                for (i in 0 until 9) {
                    val at = duration * 1000L * i / 9
                    val w = 96
                    val h = (w / item.aspect.coerceAtLeast(0.2f)).toInt().coerceAtLeast(1)
                    retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
                        ?.let { withContext(Dispatchers.Main) { frames.add(it) } }
                }
            }
            runCatching { retriever.release() }
        }
    }

    val dirty = start > 0f || end < 1f || muted || speed != 1f || turns % 4 != 0 ||
        brightness != 0f || contrast != 1f || saturation != 1f || filter != PhotoFilter.Original

    EditorFrame(
        title = "Montaje",
        dirty = dirty,
        saving = saving,
        progress = progress,
        onClose = onClose,
        onSave = {
            saving = true
            // Frees the hardware decoder: a 4K preview and a 4K encoder rarely fit together.
            player.stop()
            scope.launch {
                runCatching {
                    EditorStore.exportVideo(
                        context, item,
                        EditorStore.VideoEdit(
                            startMs = (start * duration).toLong(),
                            endMs = (end * duration).toLong(),
                            muted = muted,
                            speed = speed,
                            quarterTurns = turns,
                            brightness = brightness,
                            contrast = contrast,
                            saturation = saturation,
                            filter = filter
                        )
                    ) { progress = it }
                }.onSuccess { onSaved() }.onFailure {
                    android.util.Log.e("VideoEditor", "export failed", it)
                    saving = false
                    player.prepare()
                    onError("No se pudo exportar el vídeo")
                }
            }
        },
        preview = {
            val turned = turns % 2 != 0
            val aspect = if (turned) 1f / item.aspect else item.aspect
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val boxAspect = maxWidth / maxHeight
                val w = if (aspect > boxAspect) maxWidth else maxHeight * aspect
                val h = if (aspect > boxAspect) maxWidth / aspect else maxHeight
                val fitW = if (turned) h else w
                val fitH = if (turned) w else h
                AndroidView(
                    factory = { TextureView(it).also { v -> player.setVideoTextureView(v) } },
                    modifier = Modifier
                        .size(fitW, fitH)
                        .graphicsLayer { rotationZ = turns * 90f }
                )
            }
        },
        controls = {
            TrimBar(
                frames = frames,
                start = start,
                end = end,
                head = head,
                onChange = { s, e ->
                    if (s != start) player.seekTo((s * duration).toLong())
                    start = s
                    end = e
                }
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel((start * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = Darkroom.Accent)
                Text(
                    "DURA ${timeLabel((((end - start) * duration) / speed).toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Darkroom.Ink
                )
                Text(timeLabel((end * duration).toLong()), style = MaterialTheme.typography.labelSmall, color = Darkroom.Accent)
            }
            Box(Modifier.height(132.dp)) {
                when (tab) {
                    "Carretes" -> LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(PhotoFilter.entries.toList(), key = { it.name }) { f ->
                            FilmRoll(f, frames.firstOrNull(), f == filter) { filter = f }
                        }
                    }
                    "Color" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        EditSlider("Brillo", brightness, -0.4f..0.4f, brightness.editLabel()) { brightness = it }
                        EditSlider("Contraste", contrast, 0.6f..1.6f, (contrast - 1f).editLabel(), neutral = 1f) { contrast = it }
                        EditSlider("Saturación", saturation, 0f..2f, (saturation - 1f).editLabel(), neutral = 1f) { saturation = it }
                    }
                    "Velocidad" -> Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SPEEDS.forEach { s ->
                            ChoiceChip("${s}x".replace(".0", ""), s == speed, { speed = s }, Modifier.weight(1f))
                        }
                    }
                    else -> Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ToggleAction(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (muted) "Sin sonido" else "Con sonido", muted, Modifier.weight(1f)) { muted = !muted }
                        ToggleAction(Icons.Rounded.RotateRight, "Girar", turns % 4 != 0, Modifier.weight(1f)) { turns = (turns + 1) % 4 }
                    }
                }
            }
            TabRow(listOf("Recortar", "Carretes", "Velocidad", "Color"), tab) { tab = it }
        }
    )
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(52.dp)
            .bounceClick(pressedScale = 0.95f, feedback = { it.tick() }, onClick = onClick)
            .clip(PillShape)
            .background(if (active) Darkroom.AccentSoft else Darkroom.Raised)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        androidx.compose.material3.Icon(icon, null, tint = if (active) Darkroom.Accent else Darkroom.Ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Darkroom.Accent else Darkroom.Ink, maxLines = 1)
    }
}

/** A strip of frames with two handles; drag near one to move it. */
@Composable
private fun TrimBar(frames: List<Bitmap>, start: Float, end: Float, head: Float, onChange: (Float, Float) -> Unit) {
    val haptics = rememberHaptics()
    val startState = androidx.compose.runtime.rememberUpdatedState(start)
    val endState = androidx.compose.runtime.rememberUpdatedState(end)
    val update = androidx.compose.runtime.rememberUpdatedState(onChange)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(64.dp)
            // The handles sit where Android's back gesture starts.
            .systemGestureExclusion()
            .clip(RoundedCornerShape(10.dp))
            .background(Darkroom.Raised)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val at = down.position.x / w
                    val moveStart = abs(at - startState.value) <= abs(at - endState.value)
                    haptics.tick()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        val x = (change.position.x / w).coerceIn(0f, 1f)
                        if (moveStart) update.value(min(x, endState.value - 0.02f).coerceAtLeast(0f), endState.value)
                        else update.value(startState.value, max(x, startState.value + 0.02f).coerceAtMost(1f))
                        change.consume()
                    } while (change.pressed)
                }
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            frames.forEach { frame ->
                Image(
                    frame.asImageBitmap(), null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val l = start * size.width
            val r = end * size.width
            val dim = Color(0xB3000000)
            drawRect(dim, Offset.Zero, Size(l, size.height))
            drawRect(dim, Offset(r, 0f), Size(size.width - r, size.height))
            val edge = 5.dp.toPx()
            drawRect(Darkroom.Accent, Offset(l, 0f), Size(r - l, size.height), style = Stroke(2.5.dp.toPx()))
            drawRoundRect(Darkroom.Accent, Offset(l, 0f), Size(edge * 2, size.height), CornerRadius(edge))
            drawRoundRect(Darkroom.Accent, Offset(r - edge * 2, 0f), Size(edge * 2, size.height), CornerRadius(edge))
            val x = head * size.width
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
}
