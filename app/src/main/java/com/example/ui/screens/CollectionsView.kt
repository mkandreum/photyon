package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.example.data.MediaItem
import com.example.ui.GalleryViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.glassClickable
import com.example.ui.theme.GlassBarFill
import com.example.ui.theme.GlassBarBorder
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsView(
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favoriteItems.collectAsStateWithLifecycle()
    val rawItems by viewModel.rawMediaItems.collectAsStateWithLifecycle()
    val memories by viewModel.memoryCollections.collectAsStateWithLifecycle()
    val customAlbums by viewModel.customAlbums.collectAsStateWithLifecycle()
    val hiddenItems by viewModel.hiddenItems.collectAsStateWithLifecycle()

    // Sub-view album viewer: only a reference is kept; items are resolved from live state so
    // favorites / hidden / album changes show up immediately (no stale snapshot).
    var openAlbum by remember { mutableStateOf<AlbumRef?>(null) }
    val recentItems = remember(rawItems) { rawItems.filter { !it.isHidden } }
    fun resolveAlbum(ref: AlbumRef): List<MediaItem> = when (ref.kind) {
        AlbumKind.Favorites -> favorites
        AlbumKind.Hidden -> hiddenItems
        AlbumKind.Recent -> recentItems.take(15)
        AlbumKind.Memory -> memories.firstOrNull { it.location == ref.name }?.items.orEmpty()
        AlbumKind.Custom -> customAlbums[ref.name].orEmpty()
    }

    // MoreHoriz dropdown menu state
    var showMoreMenu by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val appContext = LocalContext.current

    BackHandler(enabled = openAlbum != null) {
        openAlbum = null
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Cerrar", color = Color(0xFF0A84FF)) } },
            title = { Text("Liquid Gallery", color = Color.White) },
            text = { Text("${rawItems.size} elementos en tu galería.", color = Color.White.copy(alpha = 0.75f)) },
            containerColor = Color(0xF21C1C1E),
            shape = RoundedCornerShape(24.dp)
        )
    }

    var showBiometricDialog by remember { mutableStateOf(false) }

    if (showBiometricDialog) {
        DisposableEffect(Unit) {
            val cancellationSignal = android.os.CancellationSignal()
            val executor = appContext.mainExecutor
            val authenticators = android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val biometricPrompt = BiometricPrompt.Builder(appContext)
                .setTitle("Acceso a Ocultos")
                .setSubtitle("Identifícate para continuar")
                .setAllowedAuthenticators(authenticators)
                .build()
            try {
                biometricPrompt.authenticate(
                    cancellationSignal, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            showBiometricDialog = false
                            openAlbum = AlbumRef(AlbumKind.Hidden, "", "Ocultos")
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            showBiometricDialog = false
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                            ) {
                                Toast.makeText(appContext, errString, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                showBiometricDialog = false
                Toast.makeText(appContext, "No se pudo iniciar la autenticación", Toast.LENGTH_SHORT).show()
            }
            onDispose { cancellationSignal.cancel() }
        }
    }

    AnimatedContent(
        targetState = openAlbum,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    initialOffsetX = { it / 3 }
                ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))) togetherWith
                (slideOutHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    targetOffsetX = { -it / 3 }
                ) + fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)))
            } else {
                (slideInHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    initialOffsetX = { -it / 3 }
                ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))) togetherWith
                (slideOutHorizontally(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                    targetOffsetX = { it / 3 }
                ) + fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)))
            }
        },
        contentKey = { it?.let { r -> "${r.kind}:${r.name}" } },
        label = "subAlbumTransition2"
    ) { subAlbum ->
        if (subAlbum != null) {
        val selectedSubAlbumItems = resolveAlbum(subAlbum)
        // Sub Grid album inspector screen!
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 12.dp, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { openAlbum = null },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(GlassBarFill)
                        .border(1.dp, GlassBarBorder, CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = subAlbum.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${selectedSubAlbumItems.size} elementos",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            }

            if (selectedSubAlbumItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Este álbum está vacío.", color = Color.Gray, fontSize = 15.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(selectedSubAlbumItems, key = { it.id }, contentType = { "media" }) { item ->
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .glassClickable(pressedScale = 0.97f) { viewModel.setDetailItem(item, selectedSubAlbumItems) }
                        ) {
                            val context = LocalContext.current
                            val imageRequest = remember(item.uri) {
                                ImageRequest.Builder(context)
                                    .data(item.uri)
                                    .size(256)
                                    .crossfade(true)
                                    .build()
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    } else {
        // Main curated Collections scroll
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // Header bar
            item(key = "header", contentType = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp, bottom = 12.dp)
                        .animateItem(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Colecciones",
                            color = Color.White,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${rawItems.size} elementos en total",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }

                    // Top Options
                    Box {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(GlassBarFill)
                                .border(1.dp, GlassBarBorder, CircleShape)
                                .glassClickable { showMoreMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = "Más opciones", tint = Color.White, modifier = Modifier.size(22.dp))
                        }

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            modifier = Modifier
                                .background(Color(0xF21C1C1E))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Ajustes", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showMoreMenu = false
                                    try {
                                        appContext.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.fromParts("package", appContext.packageName, null)
                                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (_: Exception) {}
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Acerca de", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color.White) },
                                onClick = { showMoreMenu = false; showAbout = true }
                            )
                        }
                    }
                }
            }

            // Memories section ("Recuerdos" Horizontal List with Play buttons)
            item(key = "memories", contentType = "memories") {
                Column(modifier = Modifier.padding(vertical = 16.dp).animateItem()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Recuerdos",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    }

                    if (memories.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No hay recuerdos registrados", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(memories, key = { it.location }, contentType = { "memory" }) { group ->
                                Box(
                                    modifier = Modifier
                                        .width(220.dp)
                                        .height(260.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .glassClickable(pressedScale = 0.97f) {
                                            openAlbum = AlbumRef(AlbumKind.Memory, group.location, "Recuerdos: ${group.location}")
                                        }
                                ) {
                                    val context = LocalContext.current
                                    val imageRequest = remember(group.coverUri) {
                                        ImageRequest.Builder(context)
                                            .data(group.coverUri)
                                            .size(512)
                                            .crossfade(true)
                                            .build()
                                    }
                                    // Memory cover photo
                                    AsyncImage(
                                        model = imageRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Ambient dark vignette overlay
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                                )
                                            )
                                    )

                                    // Play icon overlay bottom-rightcorner
                                    Box(
                                        modifier = Modifier
                                            .padding(16.dp)
                                            .size(36.dp)
                                            .background(Color.White.copy(alpha = 0.3f), CircleShape)
                                            .align(Alignment.BottomEnd),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Visualizar",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // Location & timeline labels on bottom-left, matching screenshots exactly!
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = group.location,
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${group.description} ${group.year}",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // FIXED SECTION ("Fijadas" horizontal scroll: Favorites, Captured, Map coordinates)
            item(key = "pinned", contentType = "pinned") {
                Column(modifier = Modifier.padding(vertical = 12.dp).animateItem()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Fijadas",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Card A: Favorites ("Favoritos")
                        item(key = "pin-fav") {
                            GlassCard(
                                modifier = Modifier
                                    .size(120.dp)
                                    .glassClickable(pressedScale = 0.96f) {
                                        openAlbum = AlbumRef(AlbumKind.Favorites, "", "Favoritos")
                                    },
                                cornerRadius = 20.dp
                            ) {
                                // Cover if exists
                                if (favorites.isNotEmpty()) {
                                    val context = LocalContext.current
                                    val firstFavoriteUri = favorites.first().uri
                                    val imageRequest = remember(firstFavoriteUri) {
                                        ImageRequest.Builder(context)
                                            .data(firstFavoriteUri)
                                            .size(256)
                                            .crossfade(true)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = imageRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.25f))
                                    )
                                }

                                // Heart icon on top right
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .size(20.dp)
                                        .align(Alignment.TopEnd)
                                )

                                Text(
                                    "Favoritos",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .align(Alignment.BottomStart)
                                )
                            }
                        }

                        // Card B: Guardados recientemente
                        item(key = "pin-recent") {
                            val recentCount = recentItems.size.coerceAtMost(99)
                            GlassCard(
                                modifier = Modifier
                                    .size(120.dp)
                                    .glassClickable(pressedScale = 0.96f) {
                                        openAlbum = AlbumRef(AlbumKind.Recent, "", "Guardados recientemente")
                                    },
                                cornerRadius = 20.dp
                            ) {
                                if (recentItems.isNotEmpty()) {
                                    val context = LocalContext.current
                                    val firstItemUri = recentItems.first().uri
                                    val imageRequest = remember(firstItemUri) {
                                        ImageRequest.Builder(context)
                                            .data(firstItemUri)
                                            .size(256)
                                            .crossfade(true)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = imageRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.25f))
                                    )
                                }

                                // Photo count badge with icon
                                Box(
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .size(36.dp)
                                        .background(Color.White.copy(alpha = 0.25f), CircleShape)
                                        .align(Alignment.TopEnd),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "$recentCount",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Icon(
                                            imageVector = Icons.Default.PhotoLibrary,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }

                                Text(
                                    "Guardados recientemente",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .align(Alignment.BottomStart)
                                )
                            }
                        }

                        // Card C: Mapa
                        item(key = "pin-map") {
                            GlassCard(
                                modifier = Modifier
                                    .size(120.dp)
                                    .glassClickable(pressedScale = 0.96f) { openMap(appContext, rawItems) },
                                cornerRadius = 20.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = Color(0xFF30D158),
                                    modifier = Modifier
                                        .size(34.dp)
                                        .align(Alignment.Center)
                                )

                                Text(
                                    "Mapa",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .align(Alignment.BottomStart)
                                )
                            }
                        }

                        // Card D: Secure Hidden Album
                        item(key = "pin-hidden") {
                            GlassCard(
                                modifier = Modifier
                                    .size(120.dp)
                                    .glassClickable(pressedScale = 0.96f) { showBiometricDialog = true },
                                cornerRadius = 20.dp
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .size(34.dp)
                                        .align(Alignment.Center)
                                )

                                Text(
                                    "Ocultas",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .align(Alignment.BottomStart)
                                )
                            }
                        }
                    }
                }
            }

            // USER CUSTOM ALBUMS LIST ("Álbumes")
            item(key = "albums-header", contentType = "albums-header") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateItem()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Álbumes creados",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (customAlbums.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No has creado ningún álbum aún.\n(Usa 'Añadir a Álbum' en la foto para crear uno)",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }

            if (customAlbums.isNotEmpty()) {
                items(customAlbums.entries.toList(), key = { "album-" + it.key }, contentType = { "album" }) { (albumName, albumItems) ->
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassClickable(pressedScale = 0.98f) {
                                    openAlbum = AlbumRef(AlbumKind.Custom, albumName, albumName)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Folder thumbnail cover
                            val context = LocalContext.current
                            val albumCoverUri = albumItems.firstOrNull()?.uri
                            val imageRequest = remember(albumCoverUri) {
                                ImageRequest.Builder(context)
                                    .data(albumCoverUri)
                                    .size(128)
                                    .crossfade(true)
                                    .build()
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1C1C1E))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(albumName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${albumItems.size} elementos", color = Color.Gray, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
    }
}

private enum class AlbumKind { Favorites, Hidden, Recent, Memory, Custom }

private data class AlbumRef(val kind: AlbumKind, val name: String, val title: String)

private fun openMap(context: android.content.Context, items: List<MediaItem>) {
    val coords = items.asSequence()
        .filter { !it.isHidden }
        .mapNotNull { it.location }
        .map { it.replace(" ", "") }
        .filter { c -> c.split(",").let { p -> p.size == 2 && p.all { v -> v.toDoubleOrNull() != null } } }
        .distinct()
        .take(10)
        .toList()
    if (coords.isEmpty()) {
        Toast.makeText(context, "Ninguna foto tiene ubicación", Toast.LENGTH_SHORT).show()
        return
    }
    val url = if (coords.size == 1) {
        "https://www.google.com/maps/search/?api=1&query=${coords[0]}"
    } else {
        val waypoints = coords.drop(1).dropLast(1).joinToString("%7C")
        "https://www.google.com/maps/dir/?api=1&origin=${coords.first()}&destination=${coords.last()}" +
            if (waypoints.isNotEmpty()) "&waypoints=$waypoints" else ""
    }
    val base = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(android.content.Intent(base).setPackage("com.google.android.apps.maps"))
    } catch (e: Exception) {
        try { context.startActivity(base) } catch (e2: Exception) {
            Toast.makeText(context, "No hay ninguna app para abrir el mapa", Toast.LENGTH_SHORT).show()
        }
    }
}
