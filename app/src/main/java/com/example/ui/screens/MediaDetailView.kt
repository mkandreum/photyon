package com.example.ui.screens

import android.media.MediaPlayer
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.input.pointer.positionChanged
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.MediaItem
import com.example.ui.GalleryViewModel
import com.example.ui.components.AnimatedPressIcon
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassSlider
import com.example.ui.components.liquidGlassBackdrop
import com.example.ui.theme.GlassBarFill
import com.example.ui.theme.GlassBarBorder
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor

class VideoController {
    var isPlaying by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var isReady by mutableStateOf(false)
    var togglePlay: () -> Unit = {}
    var seek: (Int) -> Unit = {}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaDetailView(
    viewModel: GalleryViewModel,
    onBack: () -> Unit
) {
    val activeItem by viewModel.activeDetailItem.collectAsStateWithLifecycle()
    val allItems by viewModel.detailList.collectAsStateWithLifecycle()
    
    val currentItem = activeItem ?: return
    val currentIndex = allItems.indexOfFirst { it.id == currentItem.id }
    val detailContext = LocalContext.current

    // Swiping pager initialization and synchronization state
    val pagerState = rememberPagerState(
        initialPage = currentIndex.coerceAtLeast(0),
        pageCount = { allItems.size }
    )

    // Guard pager against out-of-bounds crash when list shrinks
    LaunchedEffect(allItems.size) {
        if (allItems.isNotEmpty() && pagerState.currentPage >= allItems.size) {
            pagerState.scrollToPage(allItems.size - 1)
        }
    }

    // Flag to break cross-feedback loop between pager swipes and external navigation
    var isPagerDriven by remember { mutableStateOf(false) }
    var isInitialLoad by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(100)
        isInitialLoad = false
    }

    // When pager swipe changes the page, update the active detail item
    LaunchedEffect(pagerState.currentPage) {
        if (!isInitialLoad && pagerState.currentPage in allItems.indices) {
            val itemAtPage = allItems[pagerState.currentPage]
            if (itemAtPage.id != activeItem?.id) {
                isPagerDriven = true
                viewModel.setDetailItem(itemAtPage)
            }
        }
    }

    // When external navigation changes active item, scroll the pager
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < allItems.size && pagerState.currentPage != currentIndex) {
            if (!isPagerDriven) {
                delay(50)
                pagerState.scrollToPage(currentIndex)
            }
        }
        isPagerDriven = false
    }

    // Sliders Panel display state
    var showAdjustSliders by remember { mutableStateOf(false) }

    // Animated enter/exit transition state
    var isExiting by remember { mutableStateOf(false) }

    // Swipe-down-to-close drag state
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var isDraggingDown by remember { mutableStateOf(false) }

    var areControlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var showDetailMenu by remember { mutableStateOf(false) }

    // Auto-hide controls, but never while a panel/menu is open
    LaunchedEffect(areControlsVisible, lastInteractionTime, showAdjustSliders, showDetailMenu) {
        if (areControlsVisible && !showAdjustSliders && !showDetailMenu) {
            delay(3000)
            areControlsVisible = false
        }
    }

    LaunchedEffect(isExiting) {
        if (isExiting) {
            viewModel.clearDetailListOverride()
            delay(250)
            onBack()
        }
    }

    // Fade-only exit — grid visible underneath through transparent background
    val detailAlpha by animateFloatAsState(
        targetValue = if (!isExiting) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "detailAlpha"
    )
    val detailScale by animateFloatAsState(
        targetValue = if (!isExiting) 1f else 1.03f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 350f),
        label = "detailExitScale"
    )

    // Sliders variables representing real image correction params!
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(1f) }
    var saturation by remember { mutableStateOf(1f) }
    var rotation by remember { mutableStateOf(0f) }

    // Dialog variables for metadata customization ("Ajustar datos")
    var showAdjustMetadataDialog by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf(currentItem.displayName) }
    var newLocation by remember { mutableStateOf(currentItem.location ?: "") }
    var newDateText by remember { mutableStateOf("") }

    var dateError by remember { mutableStateOf(false) }
    var lastResetId by remember { mutableStateOf(currentItem.id) }
    fun openMetadataDialog() {
        newTitle = currentItem.displayName
        newLocation = currentItem.location ?: ""
        newDateText = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(Date(currentItem.dateAdded * 1000))
        dateError = false
        showAdjustMetadataDialog = true
    }

    LaunchedEffect(currentItem) {
        if (currentItem.id != lastResetId) {
            newTitle = currentItem.displayName
            newLocation = currentItem.location ?: ""
            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            newDateText = formatter.format(Date(currentItem.dateAdded * 1000))
            lastResetId = currentItem.id
        }
    }

    // Dialog for custom Album Adding
    var showAddAlbumDialog by remember { mutableStateOf(false) }
    var albumNameInput by remember { mutableStateOf("") }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    val detailSnackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Generate native ColorMatrix for filters dynamically
    val colorMatrixState = remember(brightness, contrast, saturation) {
        val cm = ColorMatrix()
        // Saturation adjustment
        cm.setToSaturation(saturation)
        
        // Custom matrix representation for contrast and brightness
        val array = cm.values
        array[0] = array[0] * contrast
        array[6] = array[6] * contrast
        array[12] = array[12] * contrast
        
        // Brightness offsets
        array[4] = brightness * 255f
        array[9] = brightness * 255f
        array[14] = brightness * 255f
        
        cm
    }

    val colorFilter = remember(brightness, contrast, saturation) {
        if (brightness == 0f && contrast == 1f && saturation == 1f) {
            null
        } else {
            ColorFilter.colorMatrix(colorMatrixState)
        }
    }

    var detailBottomBarRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var detailLocationRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var detailBackButtonRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var detailOptionsButtonRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var videoControlsRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val isShaderActive = android.os.Build.VERSION.SDK_INT >= 33
    var isZoomed by remember { mutableStateOf(false) }
    var detailShareRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var detailDeleteRect by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    val videoController = remember { VideoController() }

    suspend fun animateDragBack() {
        androidx.compose.animation.core.animate(
            initialValue = dragOffsetY, targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
        ) { value, _ -> dragOffsetY = value }
    }

    fun formatTime(ms: Long): String {
        val totalSec = floor(ms / 1000.0).toLong()
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val dragProgress = (dragOffsetY / 800f).coerceIn(0f, 0.5f)
                alpha = (1f - dragProgress) * detailAlpha
                translationY = dragOffsetY
                scaleX = detailScale
                scaleY = detailScale
            }
    ) {
        BackHandler(enabled = true) { isExiting = true }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassBackdrop(
                    mainBarRect = { if (areControlsVisible) detailBottomBarRect else androidx.compose.ui.geometry.Rect.Zero },
                    densityBarRect = { if (areControlsVisible) detailLocationRect else androidx.compose.ui.geometry.Rect.Zero },
                    searchBarRect = { if (areControlsVisible) detailBackButtonRect else androidx.compose.ui.geometry.Rect.Zero },
                    searchFabRect = { if (areControlsVisible) detailOptionsButtonRect else androidx.compose.ui.geometry.Rect.Zero },
                    selectBtnRect = { if (areControlsVisible) detailShareRect else androidx.compose.ui.geometry.Rect.Zero },
                    collapsedFabRect = { if (areControlsVisible) detailDeleteRect else androidx.compose.ui.geometry.Rect.Zero },
                    pageIndicatorRect = { if (areControlsVisible && currentItem.isVideo) videoControlsRect else androidx.compose.ui.geometry.Rect.Zero }
                )
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                userScrollEnabled = !isZoomed,
                beyondViewportPageCount = 1,
                key = { allItems.getOrNull(it)?.id ?: it }
            ) { pageIndex ->
            val pageItem = allItems.getOrNull(pageIndex) ?: return@HorizontalPager
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer {
                        // Parallax: background image shifts slower than pager swipe
                        val pageOffset = (pageIndex - pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)
                        translationX = pageOffset * -size.width * 0.08f
                    },
                contentAlignment = Alignment.Center
            ) {
                if (pageItem.isVideo) {
                    val isCurrentPage = pagerState.currentPage == pageIndex
                    val isCurrentState = rememberUpdatedState(isCurrentPage)
                    var resumeAfterPause by remember(pageItem.id) { mutableStateOf(false) }
                    val mediaPlayerRef = remember { mutableStateOf<MediaPlayer?>(null) }

                    var isPlaying by remember(pageItem.id) { mutableStateOf(false) }
                    var mediaPosition by remember(pageItem.id) { mutableLongStateOf(0L) }
                    var mediaDuration by remember(pageItem.id) { mutableLongStateOf(0L) }
                    var isReady by remember(pageItem.id) { mutableStateOf(false) }

                    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner, pageItem.id, isCurrentPage) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            val mp = mediaPlayerRef.value
                            if (mp != null && isCurrentPage && isReady) {
                                when (event) {
                                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                                        resumeAfterPause = try { mp.isPlaying } catch (e: IllegalStateException) { false }
                                        if (resumeAfterPause) { mp.pause(); isPlaying = false }
                                    }
                                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                                        if (resumeAfterPause) { mp.start(); isPlaying = true; resumeAfterPause = false }
                                    }
                                    else -> {}
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    DisposableEffect(pageItem.id) {
                        onDispose {
                            mediaPlayerRef.value?.let { runCatching { it.release() } }
                            mediaPlayerRef.value = null
                            isReady = false
                        }
                    }

                    LaunchedEffect(isCurrentPage) {
                        val mp = mediaPlayerRef.value ?: return@LaunchedEffect
                        if (isCurrentPage) { if (!mp.isPlaying) { mp.start(); isPlaying = true } }
                        else { if (mp.isPlaying) { mp.pause(); isPlaying = false } }
                    }

                    LaunchedEffect(isPlaying, isReady) {
                        if (isPlaying && isReady) {
                            while (true) {
                                val mp = mediaPlayerRef.value
                                if (mp != null && isReady && mp.isPlaying) {
                                    mediaPosition = mp.currentPosition.toLong()
                                    mediaDuration = mp.duration.toLong()
                                }
                                delay(250)
                            }
                        }
                    }

                    LaunchedEffect(pageItem.id, mediaDuration, mediaPosition, isPlaying, isReady) {
                        if (isCurrentPage) {
                            videoController.position = mediaPosition
                            videoController.duration = mediaDuration
                            videoController.isPlaying = isPlaying
                            videoController.isReady = isReady
                            videoController.togglePlay = {
                                val mp = mediaPlayerRef.value
                                if (mp != null) {
                                    if (mp.isPlaying) { mp.pause(); isPlaying = false }
                                    else { mp.start(); isPlaying = true }
                                }
                            }
                            videoController.seek = { pos ->
                                mediaPlayerRef.value?.let { runCatching { it.seekTo(pos) } }
                                mediaPosition = pos.toLong()
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { context ->
                                android.view.TextureView(context).apply {
                                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                            val mp = MediaPlayer()
                                            try {
                                                mp.setDataSource(context, android.net.Uri.parse(pageItem.uri))
                                                mp.setSurface(android.view.Surface(surface))
                                                mp.isLooping = true
                                                mp.setOnPreparedListener {
                                                    mediaDuration = it.duration.toLong()
                                                    isReady = true
                                                    if (isCurrentState.value) { it.start(); isPlaying = true }
                                                }
                                                mp.setOnErrorListener { _, _, _ -> isPlaying = false; isReady = false; true }
                                                mp.prepareAsync()
                                                mediaPlayerRef.value = mp
                                            } catch (e: Exception) {
                                                android.util.Log.e("DetailView", "video load failed: ${pageItem.uri}", e)
                                                runCatching { mp.release() }
                                            }
                                        }
                                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                            mediaPlayerRef.value?.let { runCatching { it.release() } }
                                            mediaPlayerRef.value = null
                                            isReady = false
                                            return true
                                        }
                                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                                    }
                                }
                            },
                            update = { },
                            modifier = Modifier.fillMaxSize()
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(pageItem.id) {
                                    detectVerticalDragGestures(
                                        onDragStart = { isDraggingDown = true },
                                        onDragEnd = {
                                            if (isDraggingDown) {
                                                if (dragOffsetY > 150f) isExiting = true
                                                else {
                                                    scope.launch {
                                                        animateDragBack()
                                                    }
                                                }
                                                isDraggingDown = false
                                            }
                                        },
                                        onDragCancel = {
                                            if (isDraggingDown) {
                                                scope.launch { animateDragBack() }
                                                isDraggingDown = false
                                            }
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            if (isDraggingDown) {
                                                dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                                if (dragOffsetY > 0f) change.consume()
                                            }
                                        }
                                    )
                                }
                                .pointerInput(pageItem.id) {
                                    detectTapGestures(
                                        onTap = {
                                            areControlsVisible = !areControlsVisible
                                            lastInteractionTime = System.currentTimeMillis()
                                        },
                                        onDoubleTap = { videoController.togglePlay() }
                                    )
                                }
                        )

                        AnimatedVisibility(
                            visible = !isPlaying && isReady,
                            enter = fadeIn(), exit = fadeOut(),
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.2f))
                                    .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Reproducir",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Interactive Zoomable image (Pan & Scale). State is only read inside
                    // graphicsLayer / pointer lambdas so gestures never recompose.
                    var scale by remember(pageItem.id) { mutableFloatStateOf(1f) }
                    var offset by remember(pageItem.id) { mutableStateOf(Offset.Zero) }
                    val zoomed by remember(pageItem.id) { derivedStateOf { scale > 1f } }
                    val isCurrentImage = pagerState.currentPage == pageIndex
                    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

                    fun clampOffset(o: Offset, sc: Float): Offset {
                        val maxX = viewSize.width * (sc - 1f) / 2f
                        val maxY = viewSize.height * (sc - 1f) / 2f
                        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                    }

                    // Reset zoom when the page is no longer the current one
                    LaunchedEffect(isCurrentImage) {
                        if (!isCurrentImage) {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    }
                    // Only the visible page may lock the pager
                    LaunchedEffect(zoomed, isCurrentImage) {
                        if (isCurrentImage) isZoomed = zoomed
                    }
                    DisposableEffect(pageItem.id) {
                        onDispose { if (isZoomed && scale > 1f) isZoomed = false }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .onSizeChanged { viewSize = it }
                            .pointerInput(pageItem.id, zoomed) {
                                detectVerticalDragGestures(
                                    onDragStart = { if (!zoomed) isDraggingDown = true },
                                    onDragEnd = {
                                        if (isDraggingDown) {
                                            if (dragOffsetY > 150f) isExiting = true
                                            else scope.launch { animateDragBack() }
                                            isDraggingDown = false
                                        }
                                    },
                                    onDragCancel = {
                                        if (isDraggingDown) {
                                            scope.launch { animateDragBack() }
                                            isDraggingDown = false
                                        }
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        if (isDraggingDown) {
                                            dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                                            if (dragOffsetY > 0f) change.consume()
                                        }
                                    }
                                )
                            }
                    ) {
                        val detailUri = remember(pageItem.uri) { android.net.Uri.parse(pageItem.uri) }
                        val thumbnailRequest = remember(pageItem.uri) {
                            ImageRequest.Builder(detailContext)
                                .data(detailUri)
                                .size(300)
                                .crossfade(true)
                                .build()
                        }
                        val imageRequest = remember(pageItem.uri) {
                            ImageRequest.Builder(detailContext)
                                .data(detailUri)
                                .size(2048)
                                .crossfade(300)
                                .allowRgb565(false)
                                .build()
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(pageItem.id) {
                                    detectTransformGesturesCustom(scaleProvider = { scale }) { _, pan, zoom, _ ->
                                        if (scale > 1f || zoom != 1f) {
                                            val newScale = (scale * zoom).coerceIn(1f, 4f)
                                            scale = newScale
                                            offset = if (newScale > 1f) clampOffset(offset + pan, newScale) else Offset.Zero
                                        }
                                    }
                                }
                                .pointerInput(pageItem.id) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            val from = scale
                                            val fromOffset = offset
                                            val target = if (from > 1f) 1f else 3f
                                            scope.launch {
                                                androidx.compose.animation.core.animate(
                                                    from, target,
                                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                                                ) { v, _ ->
                                                    scale = v
                                                    offset = if (target == 1f) fromOffset * ((v - 1f) / (from - 1f).coerceAtLeast(0.001f))
                                                    else Offset.Zero
                                                }
                                            }
                                            areControlsVisible = false
                                            lastInteractionTime = System.currentTimeMillis()
                                        },
                                        onTap = {
                                            areControlsVisible = !areControlsVisible
                                            lastInteractionTime = System.currentTimeMillis()
                                        }
                                    )
                                }
                        ) {
                            // 1. Instantly visible cached thumbnail layer
                            AsyncImage(
                                model = thumbnailRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = colorFilter,
                                onError = { android.util.Log.e("DetailView", "thumbnail load failed: ${pageItem.uri}") },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                        rotationZ = rotation
                                    }
                            )
                            // 2. Full-resolution layer fading in on top
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = pageItem.displayName,
                                contentScale = ContentScale.Fit,
                                colorFilter = colorFilter,
                                onError = { android.util.Log.e("DetailView", "full image load failed: ${pageItem.uri}") },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                        rotationZ = rotation
                                    }
                            )
                        }

                    // Zoom level indicator (visible when zoomed)
                        AnimatedVisibility(
                            visible = zoomed,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                        ) {
                            GlassCard(
                                modifier = Modifier.size(40.dp),
                                cornerRadius = 20.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${(scale * 100).toInt()}%",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }



        // --- 2. FLOATING OVERHEAD HEADER CONTROLS ---
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn() + slideInVertically { -it / 4 },
            exit = fadeOut() + slideOutVertically { -it / 4 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { isExiting = true },
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            detailBackButtonRect = coords.boundsInRoot()
                        }
                    }
                    .clip(CircleShape)
                    .background(if (isShaderActive) Color.Transparent else GlassBarFill)
                    .border(
                        1.dp,
                        if (isShaderActive) Color.Transparent else GlassBarBorder,
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Atrás",
                    tint = Color.White
                )
            }

            // Location & Date label just like Albacete 2 de junio 15:30
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            detailLocationRect = coords.boundsInRoot()
                        }
                    }
                    .clip(CircleShape)
                    .background(if (isShaderActive) Color.Transparent else GlassBarFill)
                    .border(
                        1.dp,
                        if (isShaderActive) Color.Transparent else GlassBarBorder,
                        CircleShape
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = currentItem.location ?: "Ubicación",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                val formatter = SimpleDateFormat("d 'de' MMMM, HH:mm", Locale.forLanguageTag("es-ES"))
                val dateLabel = try {
                    formatter.format(Date(currentItem.dateAdded * 1000))
                } catch (e: Exception) {
                    "Fecha"
                }
                Text(
                    text = dateLabel,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }

            // Options menu trigger button (...)
            Box {
                IconButton(
                    onClick = { showDetailMenu = true },
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                detailOptionsButtonRect = coords.boundsInRoot()
                            }
                        }
                        .clip(CircleShape)
                        .background(if (isShaderActive) Color.Transparent else GlassBarFill)
                        .border(
                            1.dp,
                            if (isShaderActive) Color.Transparent else GlassBarBorder,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Más opciones",
                        tint = Color.White
                    )
                }

                // Glassmorphic Style Custom Menu Popups
                DropdownMenu(
                    expanded = showDetailMenu,
                    onDismissRequest = { showDetailMenu = false },
                    modifier = Modifier
                        .background(Color(0xCC1C1C1E))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Copiar", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White) },
                        onClick = {
                            viewModel.copyItemToClipboard(currentItem)
                            showDetailMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicar", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.ContentPaste, contentDescription = null, tint = Color.White) },
                        onClick = {
                            viewModel.duplicateItem(currentItem)
                            showDetailMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ocultar", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.White) },
                        onClick = {
                            viewModel.hideItem(currentItem)
                            showDetailMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a Álbum", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = Color.White) },
                        onClick = {
                            showAddAlbumDialog = true
                            showDetailMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Ajustar datos", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White) },
                        onClick = {
                            openMetadataDialog()
                            showDetailMenu = false
                        }
                    )
                }
            }
        }

        } // end header AnimatedVisibility

        // --- 3. BOTTOM CAROUSEL & ACTIONS ---
        AnimatedVisibility(
            visible = areControlsVisible,
            enter = fadeIn() + slideInVertically { it / 4 },
            exit = fadeOut() + slideOutVertically { it / 4 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {

        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                    )
                )
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            // Filmstrip carousel
            val carouselListState = rememberLazyListState()
            LaunchedEffect(pagerState.currentPage) {
                if (allItems.isNotEmpty()) {
                    carouselListState.animateScrollToItem(pagerState.currentPage)
                }
            }

            LazyRow(
                state = carouselListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(allItems, key = { _, item -> item.id }) { index, item ->
                    val isSelected = index == pagerState.currentPage
                    val scaleAnim by animateFloatAsState(
                        targetValue = if (isSelected) 1.15f else 1.0f,
                        label = "carouselScale"
                    )
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .graphicsLayer {
                                scaleX = scaleAnim
                                scaleY = scaleAnim
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = 2.dp,
                                color = if (isSelected) Color(0xFF0A84FF) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                scope.launch {
                                    pagerState.scrollToPage(index)
                                }
                            }
                    ) {
                        val carouselThumbnailModel = remember(item) {
                            if (item.isVideo) {
                                val cachedFile = java.io.File(java.io.File(detailContext.cacheDir, "video_thumbnails"), "${item.id}.webp")
                                if (cachedFile.exists()) cachedFile else android.net.Uri.parse(item.uri)
                            } else {
                                android.net.Uri.parse(item.uri)
                            }
                        }
                        AsyncImage(
                            model = ImageRequest.Builder(detailContext)
                                .data(carouselThumbnailModel)
                                .size(120)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            alpha = if (isSelected) 1f else 0.55f
                        )
                        if (item.isVideo) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(Alignment.Center)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // B) Tool bar - Simplified: Share left, Favorite/Info/Edit center, Delete right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // LEFT: Share only
                AnimatedPressIcon(
                    onClick = {
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = if (currentItem.isVideo) "video/*" else "image/*"
                            putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(currentItem.uri))
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            detailContext.startActivity(
                                android.content.Intent.createChooser(shareIntent, "Compartir")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            scope.launch { detailSnackbarHostState.showSnackbar("No se puede compartir este elemento") }
                        }
                    },
                    contentDescription = "Compartir",
                    tint = Color.White,
                    modifier = Modifier
                        .size(44.dp)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                detailShareRect = coords.boundsInRoot()
                            }
                        }
                        .clip(CircleShape)
                        .background(if (isShaderActive) Color.Transparent else GlassBarFill)
                        .border(
                            1.dp,
                            if (isShaderActive) Color.Transparent else GlassBarBorder,
                            CircleShape
                        ),
                    icon = { Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                )

                // CENTER: GlassCard with Favorite + Info + Edit
                GlassCard(
                    modifier = Modifier
                        .width(180.dp)
                        .height(44.dp)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                detailBottomBarRect = coords.boundsInRoot()
                            }
                        },
                    cornerRadius = 22.dp,
                    borderAlpha = if (isShaderActive) 0f else 1f,
                    backgroundAlpha = if (isShaderActive) 0f else 1f
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Favorite with pulse
                        val favScale by animateFloatAsState(
                            targetValue = if (currentItem.isFavorite) 1.25f else 1f,
                            animationSpec = spring(dampingRatio = 0.3f, stiffness = 600f),
                            label = "favScale"
                        )
                        val favGlow by animateFloatAsState(
                            targetValue = if (currentItem.isFavorite) 1f else 0f,
                            animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
                            label = "favGlow"
                        )
                        Box(modifier = Modifier.size(36.dp)) {
                            if (favGlow > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { alpha = favGlow * 0.4f; scaleX = favScale; scaleY = favScale }
                                        .background(Color.Red.copy(alpha = 0.3f), CircleShape)
                                )
                            }
                            AnimatedPressIcon(
                                onClick = { viewModel.toggleFavorite(currentItem) },
                                contentDescription = "Favorito",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape),
                                tint = if (currentItem.isFavorite) Color(0xFF0A84FF) else Color.White,
                                icon = {
                                    Icon(
                                        imageVector = if (currentItem.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = null,
                                        tint = if (currentItem.isFavorite) Color(0xFF0A84FF) else Color.White,
                                        modifier = Modifier.size(18.dp).graphicsLayer(scaleX = favScale, scaleY = favScale)
                                    )
                                }
                            )
                        }

                        // Info
                        AnimatedPressIcon(
                            onClick = { openMetadataDialog() },
                            contentDescription = "Info",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            tint = Color.White,
                            icon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        )

                        // Edit (tune / adjust)
                        AnimatedPressIcon(
                            onClick = { showAdjustSliders = !showAdjustSliders },
                            contentDescription = "Ajustar filtros",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (showAdjustSliders) Color(0xFF0A84FF).copy(alpha = 0.2f) else Color.Transparent
                                ),
                            tint = if (showAdjustSliders) Color(0xFF0A84FF) else Color.White,
                            icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = if (showAdjustSliders) Color(0xFF0A84FF) else Color.White, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }

                // RIGHT: Delete only
                AnimatedPressIcon(
                    onClick = { showDeleteConfirm = true },
                    contentDescription = "Eliminar",
                    modifier = Modifier
                        .size(44.dp)
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached) {
                                detailDeleteRect = coords.boundsInRoot()
                            }
                        }
                        .clip(CircleShape)
                        .background(if (isShaderActive) Color.Transparent else GlassBarFill)
                        .border(
                            1.dp,
                            if (isShaderActive) Color.Transparent else GlassBarBorder,
                            CircleShape
                        ),
                    tint = Color.White,
                    icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                )
            }
        }

        } // end bottom AnimatedVisibility

        // --- 4. VIDEO CONTROLS (glass floating bar con seek animado) ---
        var isDraggingSeek by remember { mutableStateOf(false) }
        var dragFraction by remember { mutableStateOf(0f) }
        val seekFraction = if (isDraggingSeek) dragFraction
            else if (videoController.duration > 0) videoController.position.toFloat() / videoController.duration.toFloat()
            else 0f

        AnimatedVisibility(
            visible = areControlsVisible && currentItem.isVideo && videoController.isReady,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                initialOffsetY = { it }
            ) + fadeIn(),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 350f),
                targetOffsetY = { it }
            ) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 130.dp)
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        videoControlsRect = coords.boundsInRoot()
                    }
                }
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 28.dp,
                borderAlpha = 0.8f,
                backgroundAlpha = 0.9f
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Barra de progreso de cristal con thumb animado
                    Slider(
                        value = seekFraction,
                        onValueChange = { f ->
                            if (!isDraggingSeek) {
                                isDraggingSeek = true
                                dragFraction = if (videoController.duration > 0)
                                    videoController.position.toFloat() / videoController.duration.toFloat() else 0f
                            }
                            dragFraction = f
                        },
                        onValueChangeFinished = {
                            isDraggingSeek = false
                            videoController.seek((dragFraction * videoController.duration).toInt())
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White.copy(alpha = 0.8f),
                            inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(
                                if (isDraggingSeek) (dragFraction * videoController.duration).toLong()
                                else videoController.position
                            ),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Play/Pause con animación de escala
                        val btnScale by animateFloatAsState(
                            targetValue = if (isDraggingSeek) 0.8f else 1f,
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
                            label = "playBtnScale"
                        )
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .graphicsLayer { scaleX = btnScale; scaleY = btnScale }
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable(enabled = !isDraggingSeek) { videoController.togglePlay() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (videoController.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (videoController.isPlaying) "Pausar" else "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Text(
                            text = formatTime(videoController.duration),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // --- 5. FLOATING GLASS FILTERS ADJUST PANELS (slide up with spring) ---
        AnimatedVisibility(
            visible = showAdjustSliders,
            enter = slideInVertically(
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 350f),
                initialOffsetY = { it }
            ) + fadeIn(),
            exit = slideOutVertically(
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 250f),
                targetOffsetY = { it }
            ) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 150.dp)
                .padding(horizontal = 16.dp)
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Filtros de Cristal",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    
                    // Brightness slider
                    GlassSlider(
                        value = brightness,
                        onValueChange = { brightness = it },
                        valueRange = -0.5f..0.5f,
                        label = "Brillo"
                    )

                    GlassSlider(
                        value = contrast,
                        onValueChange = { contrast = it },
                        valueRange = 0.5f..2.0f,
                        label = "Contraste"
                    )

                    GlassSlider(
                        value = saturation,
                        onValueChange = { saturation = it },
                        valueRange = 0f..2.0f,
                        label = "Saturación"
                    )

                    GlassSlider(
                        value = rotation,
                        onValueChange = { rotation = it },
                        valueRange = 0f..360f,
                        label = "Girar"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = {
                            brightness = 0f
                            contrast = 1f
                            saturation = 1f
                            rotation = 0f
                        }) {
                            Text("Restaurar", color = Color.White.copy(alpha = 0.6f))
                        }
                        TextButton(onClick = { showAdjustSliders = false }) {
                            Text("Guardar", color = Color(0xFF0A84FF), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = detailSnackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)
        )

        // --- 6. ADJUST METADATA DIALOG ("Ajustar datos") ---
        if (showAdjustMetadataDialog) {
            AlertDialog(
                onDismissRequest = { showAdjustMetadataDialog = false },
                title = { Text("Ajustar datos de Imagen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { newTitle = it },
                            label = { Text("Título / Nombre de Archivo") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = newLocation,
                            onValueChange = { newLocation = it },
                            label = { Text("Ubicación (ej: Albacete, Peñíscola)") },
                            leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = newDateText,
                            onValueChange = {
                                newDateText = it
                                dateError = false
                            },
                            label = { Text("Fecha (Formato: DD/MM/AAAA hh:mm)") },
                            leadingIcon = { Icon(Icons.Outlined.DateRange, contentDescription = null) },
                            singleLine = true,
                            isError = dateError,
                            supportingText = if (dateError) {{ Text("Fecha inválida. Usa DD/MM/AAAA hh:mm", color = MaterialTheme.colorScheme.error) }} else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable {
                                val parsedDate = try {
                                    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                    sdf.parse(newDateText)?.time
                                } catch (e: Exception) {
                                    null
                                }
                                if (parsedDate == null && newDateText.isNotBlank()) {
                                    dateError = true
                                    return@clickable
                                }
                                viewModel.changeMetadata(
                                    item = currentItem,
                                    title = newTitle,
                                    location = newLocation,
                                    dateMs = parsedDate
                                )
                                dateError = false
                                showAdjustMetadataDialog = false
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Actualizar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                },
                dismissButton = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .clickable { showAdjustMetadataDialog = false }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cancelar", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
            )
        }

        // --- 7. ADD TO CUSTOM ALBUM DIALOG ---
        if (showAddAlbumDialog) {
            AlertDialog(
                onDismissRequest = { showAddAlbumDialog = false },
                title = { Text("Añadir a un Álbum") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Introduce el nombre del álbum en el que deseas añadir este elemento:")
                        OutlinedTextField(
                            value = albumNameInput,
                            onValueChange = { albumNameInput = it },
                            placeholder = { Text("Ej. Vacaciones, Familia") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable {
                                if (albumNameInput.isNotBlank()) {
                                    viewModel.addToAlbum(currentItem, albumNameInput.trim())
                                    showAddAlbumDialog = false
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Añadir", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                },
                dismissButton = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .clickable { showAddAlbumDialog = false }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cancelar", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
            )
        }

        // --- 8. DELETE CONFIRMATION DIALOG ---
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("¿Ocultar este elemento?") },
                text = { Text("El elemento se ocultará de la galería. Puedes recuperarlo desde la sección Ocultas.") },
                confirmButton = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.25f))
                            .border(1.dp, Color.Red.copy(alpha = 0.5f), CircleShape)
                            .clickable {
                                viewModel.deleteItem(currentItem)
                                showDeleteConfirm = false
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Ocultar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                },
                dismissButton = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .clickable { showDeleteConfirm = false }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Cancelar", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                    }
                }
            )
        }
    }
}

suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTransformGesturesCustom(
    panZoomLock: Boolean = false,
    scaleProvider: () -> Float,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit
) {
    awaitEachGesture {
        var rotation = 0f
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        var lockedToPanZoomRatio = false

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val rotationChange = event.calculateRotation()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    rotation += rotationChange
                    pan += panChange

                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = kotlin.math.abs(1 - zoom) * centroidSize
                    val rotationMotion = kotlin.math.abs(rotation) * 45f
                    val panMotion = pan.getDistance()

                    if (zoomMotion > touchSlop ||
                        rotationMotion > touchSlop ||
                        panMotion > touchSlop
                    ) {
                        pastTouchSlop = true
                        lockedToPanZoomRatio = panZoomLock && rotationMotion < touchSlop
                    }
                }

                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val effectiveRotation = if (lockedToPanZoomRatio) 0f else rotationChange
                    if (effectiveRotation != 0f ||
                        zoomChange != 1f ||
                        panChange != Offset.Zero
                    ) {
                        onGesture(centroid, panChange, zoomChange, effectiveRotation)
                    }
                    val currentScale = scaleProvider()
                    event.changes.forEach {
                        if (it.positionChanged()) {
                            if (currentScale > 1f || event.changes.size > 1) {
                                it.consume()
                            }
                        }
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}
