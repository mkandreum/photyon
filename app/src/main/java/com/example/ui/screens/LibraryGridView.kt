package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import coil.compose.AsyncImage
import com.example.ui.components.glassClickable
import coil.request.ImageRequest
import coil.request.CachePolicy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.MediaItem
import com.example.ui.GalleryViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.LiquidLensCard
import com.example.ui.theme.GlassBarFill
import com.example.ui.theme.GlassBarBorder
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import com.example.ui.theme.snappySpring
import androidx.compose.animation.animateColorAsState
import java.text.SimpleDateFormat
import java.util.*


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridView(
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier,
    onScrollStateChanged: (Boolean) -> Unit = {},
    onIsAtTop: (Boolean) -> Unit = {},
    onSelectBtnRectChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onSearchFabRectChanged: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    onSearchClick: () -> Unit = {},
    isSearchActive: Boolean = false
) {
    val items by viewModel.visibleMediaItems.collectAsStateWithLifecycle()
    val isShaderActive = android.os.Build.VERSION.SDK_INT >= 33
    val reversedItems = remember(items) { items.reversed() }
    val gridMode by viewModel.gridMode.collectAsStateWithLifecycle()
    val isSelectionMode by viewModel.isSelectionModeActive.collectAsStateWithLifecycle()
    val selectedItems by viewModel.selectedItems.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val groupedYears by viewModel.groupedYears.collectAsStateWithLifecycle()
    val groupedMonths by viewModel.groupedMonths.collectAsStateWithLifecycle()
    val drilledDownMonths by viewModel.drilledDownMonths.collectAsStateWithLifecycle()
    val selectedYear by viewModel.selectedYear.collectAsStateWithLifecycle()
    val selectedMonth by viewModel.selectedMonth.collectAsStateWithLifecycle()

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val isScrollActive = gridState.isScrollInProgress

    LaunchedEffect(isScrollActive) {
        onScrollStateChanged(isScrollActive)
    }

    val isAtTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 50 }
    }
    LaunchedEffect(isAtTop) { onIsAtTop(isAtTop) }

    LaunchedEffect(selectedYear, selectedMonth, isSearchActive) {
        if (selectedYear != null || selectedMonth != null || isSearchActive) {
            onSearchFabRectChanged(androidx.compose.ui.geometry.Rect.Zero)
        }
    }

    val isFirstLoad = remember { mutableStateOf(true) }
    LaunchedEffect(reversedItems) {
        if (reversedItems.isNotEmpty() && isFirstLoad.value) {
            gridState.scrollToItem(reversedItems.size - 1)
            isFirstLoad.value = false
        }
    }


    // Context long-press custom glass overlay menu variables
    var longPressedItem by remember { mutableStateOf<MediaItem?>(null) }
    var lastLongPressed by remember { mutableStateOf<MediaItem?>(null) }
    if (longPressedItem != null) lastLongPressed = longPressedItem

    BackHandler(enabled = isSelectionMode) {
        viewModel.toggleSelectionMode()
    }

    BackHandler(enabled = longPressedItem != null) {
        longPressedItem = null
    }

    // Add to Custom Album state
    var showAddAlbumDialog by remember { mutableStateOf(false) }
    var albumNameInput by remember { mutableStateOf("") }
    
    // Custom adjust metadata state
    var showAdjustMetadataDialog by remember { mutableStateOf(false) }
    var adjustTitle by remember { mutableStateOf("") }
    var adjustLocation by remember { mutableStateOf("") }
    var adjustDateText by remember { mutableStateOf("") }

    // Shimmer loading animation state
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlphaState = shimmerTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )
    val shimmerAlpha: () -> Float = { shimmerAlphaState.value }

    // Snackbar for undo actions
    val snackbarHostState = remember { SnackbarHostState() }

    // Press scale animation state
    var pressedItemId by remember { mutableStateOf<String?>(null) }

    // Coroutine scope for snackbar actions
    val snackbarScope = rememberCoroutineScope()

    // Dynamic grid columns based on mode
    val isGroupedCardView = (gridMode == "Años" && selectedMonth == null) ||
            (gridMode == "Meses" && selectedMonth == null)
    val columnsCount = when {
        isGroupedCardView -> 1
        gridMode == "Meses" -> 3
        else -> 4
    }

    val flatItems = remember(items, reversedItems, gridMode, selectedYear, selectedMonth, groupedMonths, drilledDownMonths) {
        when {
            gridMode == "Meses" && selectedMonth != null ->
                (groupedMonths.find { it.month == selectedMonth }?.items ?: emptyList()).reversed()
            gridMode == "Años" && selectedYear != null && selectedMonth != null ->
                (drilledDownMonths.find { it.month == selectedMonth }?.items ?: emptyList()).reversed()
            else -> reversedItems
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // --- 1. MAIN GRID CONTENT ---
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columnsCount),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 106.dp, bottom = 130.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (items.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 70.dp)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .background(Color.White.copy(alpha = 0.05f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = Color.LightGray,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Todavía no hay fotos ni vídeos en tu dispositivo.",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                    }
                }
            } else if (gridMode == "Años" && selectedYear == null) {
                groupedYears.forEach { section ->
                    item(key = "y_${section.year}", span = { GridItemSpan(maxLineSpan) }, contentType = "section") {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .clickable { viewModel.drillDownYear(section.year) },
                            cornerRadius = 16.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(section.year, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Text("${section.items.size} elementos", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            } else if (gridMode == "Años" && selectedYear != null && selectedMonth == null) {
                drilledDownMonths.forEach { section ->
                    item(key = "dm_${section.month}", span = { GridItemSpan(maxLineSpan) }, contentType = "section") {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .clickable { viewModel.drillDownMonth(section.month) },
                            cornerRadius = 14.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(section.month, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("${section.items.size} fotos", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            } else if (gridMode == "Meses" && selectedMonth == null) {
                groupedMonths.forEach { section ->
                    item(key = "m_${section.month}", span = { GridItemSpan(maxLineSpan) }, contentType = "section") {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .clickable { viewModel.drillDownMonth(section.month) },
                            cornerRadius = 14.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(section.month, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text("${section.items.size} fotos", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            } else {
                items(flatItems, key = { it.id }, contentType = { "media" }) { item ->
                    GalleryGridItemCell(
                        item = item,
                        isSelected = selectedItems.contains(item.id),
                        isSelectionMode = isSelectionMode,
                        gridMode = "Todo",
                        cardHeight = 90.dp,
                        shimmerAlpha = shimmerAlpha,
                        onItemClick = {
                            if (isSelectionMode) viewModel.toggleItemSelection(item.id)
                            else viewModel.setDetailItem(item)
                        },
                        onItemLongClick = { longPressedItem = item },
                        viewModel = viewModel
                    )
                }
            }
        }

        // Fixed header overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.2f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 12.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedYear != null || selectedMonth != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                when {
                                    selectedMonth != null && selectedYear != null -> {
                                        viewModel.drillDownYear(selectedYear!!)
                                    }
                                    selectedYear != null -> viewModel.resetDrillDown()
                                    selectedMonth != null -> viewModel.resetDrillDown()
                                }
                            }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Atrás",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                selectedMonth != null && gridMode == "Meses" -> selectedMonth!!
                                selectedMonth != null && selectedYear != null -> selectedMonth!!
                                selectedYear != null -> selectedYear!!
                                else -> selectedMonth ?: ""
                            },
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Fototeca",
                            color = Color.White,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (items.isEmpty()) "Sin elementos" else "${items.size} elementos",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isSearchActive && selectedYear == null && selectedMonth == null) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .onGloballyPositioned { coords ->
                                    if (coords.isAttached) {
                                        onSearchFabRectChanged(coords.boundsInRoot())
                                    } else {
                                        onSearchFabRectChanged(androidx.compose.ui.geometry.Rect.Zero)
                                    }
                                }
                                .clip(CircleShape)
                                .background(if (isShaderActive) Color.Transparent else GlassBarFill)
                                .border(
                                    1.dp,
                                    if (isShaderActive) Color.Transparent else GlassBarBorder,
                                    CircleShape
                                )
                                .glassClickable(onClick = onSearchClick),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Buscar",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .onGloballyPositioned { coords ->
                                if (coords.isAttached) {
                                    onSelectBtnRectChanged(coords.boundsInRoot())
                                } else {
                                    onSelectBtnRectChanged(androidx.compose.ui.geometry.Rect.Zero)
                                }
                            }
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSelectionMode) Color.White
                                else if (isShaderActive) Color.Transparent else GlassBarFill
                            )
                            .border(
                                1.dp,
                                if (isSelectionMode || isShaderActive) Color.Transparent else GlassBarBorder,
                                RoundedCornerShape(20.dp)
                            )
                            .glassClickable { viewModel.toggleSelectionMode() }
                            .padding(horizontal = 18.dp, vertical = 0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isSelectionMode) "Cancelar" else "Seleccionar",
                            color = if (isSelectionMode) Color.Black else Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // --- 2. MULTI-SELECTION BOTTOM ACTION FLOATING PANEL ---
        AnimatedVisibility(
            visible = isSelectionMode && selectedItems.isNotEmpty(),
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
                .padding(bottom = 120.dp, start = 16.dp, end = 16.dp)
        ) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                cornerRadius = 32.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedItems.size} seleccionados",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Bulk Favorite
                        IconButton(onClick = { viewModel.bulkFavorite() }) {
                            Icon(Icons.Default.FavoriteBorder, contentDescription = "Me gusta en lote", tint = Color.White)
                        }

                        // Bulk Hide
                        IconButton(onClick = { viewModel.bulkHide() }) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = "Ocultar en lote", tint = Color.White)
                        }

                        // Bulk Trash / Delete
                        IconButton(onClick = { viewModel.bulkDelete() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar en lote", tint = Color.White)
                        }
                    }
                }
            }
        }

        // Density segment selector moved to MainActivity for coordinated transition overlay

        // --- 4. LIQUID LENS LONG-PRESS CONTEXT OVERLAY ---
        AnimatedVisibility(
            visible = longPressedItem != null,
            enter = fadeIn(animationSpec = spring(dampingRatio = 0.5f, stiffness = 350f)),
            exit = fadeOut(animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)),
            modifier = Modifier.fillMaxSize()
        ) {
            val cell = lastLongPressed ?: return@AnimatedVisibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f))
                    .clickable { longPressedItem = null },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .clickable(enabled = false) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Liquid magnifying lens around the image preview
                    LiquidLensCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    ) {
                        AsyncImage(
                            model = cell.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Small location badge
                        if (cell.location != null) {
                            Box(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .align(Alignment.TopStart)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(cell.location, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // LIQUID GLASS CONTEXT CONTROL BAR
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 24.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        viewModel.copyItemToClipboard(cell)
                                        longPressedItem = null
                                    }
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Copiar", color = Color.White, fontSize = 12.sp)
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        viewModel.duplicateItem(cell)
                                        longPressedItem = null
                                    }
                                ) {
                                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Duplicar", color = Color.White, fontSize = 12.sp)
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable {
                                        viewModel.hideItem(cell)
                                        longPressedItem = null
                                        snackbarScope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Elemento ocultado",
                                                actionLabel = "Deshacer",
                                                duration = SnackbarDuration.Short
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                viewModel.unhideItem(cell)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Outlined.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Ocultar", color = Color.White, fontSize = 12.sp)
                                }
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        albumNameInput = ""
                                showAddAlbumDialog = true
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.AddBox, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Añadir", color = Color.White, fontSize = 14.sp)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        adjustTitle = cell.displayName
                                        adjustLocation = cell.location ?: ""
                                        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                        adjustDateText = fmt.format(Date(cell.dateAdded * 1000))
                                        showAdjustMetadataDialog = true
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Info, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Ajustar datos", color = Color.White, fontSize = 14.sp)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // --- 5. LONG PRESS NESTED DIALOG FOR ALBUMS ---
        if (showAddAlbumDialog && longPressedItem != null) {
            AlertDialog(
                onDismissRequest = { showAddAlbumDialog = false },
                title = { Text("Añadir a un Álbum") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Escribe el nombre del Álbum para guardar este elemento:")
                        OutlinedTextField(
                            value = albumNameInput,
                            onValueChange = { albumNameInput = it },
                            placeholder = { Text("Ej. Viajes, Amigos") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .graphicsLayer { alpha = if (albumNameInput.isNotBlank()) 1f else 0.4f }
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable(enabled = albumNameInput.isNotBlank()) {
                                val item = longPressedItem ?: return@clickable
                                if (albumNameInput.isNotBlank()) {
                                    viewModel.addToAlbum(item, albumNameInput.trim())
                                    showAddAlbumDialog = false
                                    longPressedItem = null
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

        // --- 6. LONG PRESS NESTED DIALOG FOR METADATA ("Ajustar datos") ---
        if (showAdjustMetadataDialog && longPressedItem != null) {
            AlertDialog(
                onDismissRequest = { showAdjustMetadataDialog = false },
                title = { Text("Ajustar datos") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = adjustTitle,
                            onValueChange = { adjustTitle = it },
                            label = { Text("Título") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = adjustLocation,
                            onValueChange = { adjustLocation = it },
                            label = { Text("Ubicación") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = adjustDateText,
                            onValueChange = { adjustDateText = it },
                            label = { Text("Fecha (Formato: DD/MM/AAAA hh:mm)") },
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
                                val item = longPressedItem ?: return@clickable
                                val epochDate = try {
                                    val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                    df.parse(adjustDateText)?.time
                                } catch (e: Exception) {
                                    null
                                }
                                viewModel.changeMetadata(
                                    item = item,
                                    title = adjustTitle,
                                    location = adjustLocation,
                                    dateMs = epochDate
                                )
                                showAdjustMetadataDialog = false
                                longPressedItem = null
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Guardar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

        // --- 7. SNACKBAR HOST FOR UNDO ACTIONS ---
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryGridItemCell(
    item: MediaItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    gridMode: String,
    cardHeight: Dp,
    shimmerAlpha: () -> Float,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    viewModel: GalleryViewModel
) {
    val formattedDuration = item.durationString
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val itemPressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = snappySpring,
        label = "itemPressScale"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onItemClick,
                onLongClick = onItemLongClick
            )
    ) {
        var isLoaded by remember { mutableStateOf(false) }

        if (!isLoaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(Color.DarkGray.copy(alpha = shimmerAlpha()))
                    }
            )
        }

        val thumbnailSize = when (gridMode) {
            "Años" -> 512
            "Meses" -> 256
            "Todo" -> 180
            else -> 256
        }
        val context = LocalContext.current

        val thumbFile by produceState<java.io.File?>(
            initialValue = com.example.data.ThumbnailStore.fileIfExists(context, item.id),
            key1 = item.id
        ) {
            if (value == null) value = com.example.data.ThumbnailStore.ensure(context, item)
        }
        val thumb = thumbFile
        if (thumb != null) {
            val imageRequest = remember(thumb, thumbnailSize) {
                ImageRequest.Builder(context)
                    .data(thumb)
                    .size(thumbnailSize)
                    .crossfade(100)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                onSuccess = { isLoaded = true },
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = itemPressScale
                        scaleY = itemPressScale
                    }
            )
        }

        if (isSelectionMode) {
            val overlayColor by animateColorAsState(
                if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                animationSpec = tween(120), label = "selOverlay"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor)
            )
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .border(1.5.dp, Color.White, CircleShape)
                    .align(Alignment.TopEnd),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Seleccionado",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        if (item.isVideo && formattedDuration != null) {
            Row(
                modifier = Modifier
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .align(Alignment.BottomEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = formattedDuration,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (gridMode == "Años" && item.location != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
                    .padding(12.dp)
                    .align(Alignment.BottomStart)
            ) {
                Column {
                    Text(
                        item.location,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        item.displayName,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
