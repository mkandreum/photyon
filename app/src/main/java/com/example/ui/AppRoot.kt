package com.example.ui

import android.Manifest
import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Album
import com.example.data.Library
import com.example.data.MediaItem
import com.example.data.Thumbnails
import com.example.ui.components.ApertureButton
import com.example.ui.components.FloatingNav
import com.example.ui.editor.Editor
import com.example.ui.stories.StoryPlayer
import com.example.ui.components.NavItem
import com.example.ui.components.PillShape
import com.example.ui.components.bounceClick
import com.example.ui.components.floatingShadow
import com.example.ui.components.rememberHaptics
import com.example.ui.theme.Darkroom
import com.example.ui.timeline.Timeline
import com.example.ui.timeline.TimelineState
import com.example.ui.timeline.rememberTimelineState
import com.example.ui.viewer.Viewer
import com.example.ui.viewer.ViewerActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Access { None, Partial, Full }

private val MEDIA_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
)

private fun Context.has(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.mediaAccess(): Access = when {
    has(Manifest.permission.READ_MEDIA_IMAGES) && has(Manifest.permission.READ_MEDIA_VIDEO) -> Access.Full
    has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
    else -> Access.None
}

/** Which list a collection or the viewer is showing. Resolved live so edits and deletions show at once. */
private sealed interface Source {
    data object Timeline : Source
    data object Favorites : Source
    data object Videos : Source
    data object Hidden : Source
    data object Search : Source
    data class AlbumRef(val id: String) : Source
    data class MemoryRef(val yearsAgo: Int) : Source
    data class YearRef(val year: Int) : Source
    data class PlaceRef(val id: String) : Source
    data class HiddenAlbum(val id: String) : Source
}

private fun Library.itemsOf(source: Source, search: List<MediaItem>): List<MediaItem> = when (source) {
    Source.Timeline -> timeline
    Source.Favorites -> favorites
    Source.Videos -> videos
    Source.Hidden -> hidden
    Source.Search -> search
    is Source.AlbumRef -> albums.firstOrNull { it.id == source.id }?.items.orEmpty()
    is Source.MemoryRef -> memories.firstOrNull { it.yearsAgo == source.yearsAgo }?.items.orEmpty()
    is Source.YearRef -> years.firstOrNull { it.year == source.year }?.items.orEmpty()
    is Source.PlaceRef -> places.firstOrNull { it.id == source.id }?.items.orEmpty()
    is Source.HiddenAlbum -> hiddenAlbums.firstOrNull { it.id == source.id }?.items.orEmpty()
}

private fun Library.titleOf(source: Source): String = when (source) {
    Source.Favorites -> "Favoritas"
    Source.Videos -> "Vídeos"
    Source.Hidden -> "Oculto"
    is Source.AlbumRef -> albums.firstOrNull { it.id == source.id }?.name ?: "Álbum"
    is Source.MemoryRef -> if (source.yearsAgo == 1) "Hace 1 año" else "Hace ${source.yearsAgo} años"
    is Source.YearRef -> source.year.toString()
    is Source.PlaceRef -> places.firstOrNull { it.id == source.id }?.name ?: "Lugar"
    is Source.HiddenAlbum -> hiddenAlbums.firstOrNull { it.id == source.id }?.name ?: "Oculto"
    else -> ""
}

@Stable
private class ViewerRequest(val source: Source, val startId: String)

@Composable
fun AppRoot(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var access by remember { mutableStateOf(context.mediaAccess()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askedOnce = true
        access = context.mediaAccess()
        viewModel.onPermissionChanged()
    }
    // The user can change access from system settings or the photo picker while we are in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = context.mediaAccess()
                if (now != access) {
                    access = now
                    viewModel.onPermissionChanged()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { if (access == Access.None) permissionLauncher.launch(MEDIA_PERMISSIONS) }

    // Town names come from each photo's GPS, which Android keeps behind its own permission.
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPhotoLocationAnswered()
    }
    LaunchedEffect(access) {
        if (access != Access.None) {
            if (context.has(Manifest.permission.ACCESS_MEDIA_LOCATION)) viewModel.onPhotoLocationAnswered()
            else if (viewModel.shouldAskPhotoLocation()) locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }

    if (access == Access.None) {
        val canAsk = !askedOnce || (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES) == true
        PermissionScreen(
            denied = !canAsk,
            onGrant = { permissionLauncher.launch(MEDIA_PERMISSIONS) },
            onOpenSettings = { context.openAppSettings() }
        )
        return
    }

    val library by viewModel.library.collectAsStateWithLifecycle()
    val density by viewModel.density.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(TAB_PHOTOS) }
    var collection by remember { mutableStateOf<Source?>(null) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var hiddenUnlocked by remember { mutableStateOf(false) }
    var hiddenHome by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var storyAt by remember { mutableStateOf<Int?>(null) }
    var albumFor by remember { mutableStateOf<List<String>?>(null) }

    val seenStories by viewModel.seenStories.collectAsStateWithLifecycle()
    val mainState = rememberTimelineState()
    val searchState = rememberTimelineState()
    val collectionState = remember(collection) { TimelineState(androidx.compose.foundation.lazy.LazyListState()) }

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.clearSelection()
            viewModel.showNotice("Movido a la papelera")
        }
    }
    fun trash(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val pending = MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, true)
        trashLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
    }

    fun openHidden() {
        if (hiddenUnlocked) {
            hiddenHome = true
            return
        }
        val activity = context as? Activity ?: return
        val strongOrLock = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val manager = context.getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(strongOrLock) != BiometricManager.BIOMETRIC_SUCCESS) {
            viewModel.showNotice("Activa la huella o un bloqueo de pantalla para usar Oculto")
            return
        }
        BiometricPrompt.Builder(activity)
            .setTitle("Oculto")
            .setSubtitle("Usa tu huella para entrar")
            .setAllowedAuthenticators(strongOrLock)
            .build()
            .authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        hiddenUnlocked = true
                        hiddenHome = true
                    }
                }
            )
    }

    val isHiddenSource: (Source?) -> Boolean = { it == Source.Hidden || it is Source.HiddenAlbum }
    val showingHidden = hiddenHome || isHiddenSource(collection) || isHiddenSource(viewer?.source)

    // Hidden content locks again as soon as the app leaves the screen, and never shows in recents or screenshots.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                hiddenUnlocked = false
                hiddenHome = false
                if (isHiddenSource(collection)) collection = null
                if (isHiddenSource(viewer?.source)) viewer = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(showingHidden) {
        val window = (context as? Activity)?.window
        if (showingHidden) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val activeSource = collection ?: if (tab == TAB_SEARCH) Source.Search else Source.Timeline
    val activeState = when {
        collection != null -> collectionState
        tab == TAB_SEARCH -> searchState
        else -> mainState
    }
    val selectionItems = remember(selected, library, results, activeSource) {
        library.itemsOf(activeSource, results).filter { it.id in selected }
    }
    val openViewer: (Source, MediaItem) -> Unit = { source, item -> viewer = ViewerRequest(source, item.id) }

    BackHandler(enabled = selected.isNotEmpty()) { viewModel.clearSelection() }
    BackHandler(enabled = selected.isEmpty() && viewer == null && collection != null) { collection = null }
    BackHandler(enabled = selected.isEmpty() && viewer == null && collection == null && hiddenHome) { hiddenHome = false }
    BackHandler(enabled = selected.isEmpty() && viewer == null && collection == null && !hiddenHome && tab != TAB_PHOTOS) { tab = TAB_PHOTOS }

    // The nav slides away while reading downwards and returns on any upward scroll.
    var navShown by remember { mutableStateOf(true) }
    val navConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) navShown = false else if (available.y > 6f) navShown = true
                return Offset.Zero
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Darkroom.Canvas)
            .nestedScroll(navConnection)
    ) {
        // The timeline stays composed so returning to it is instant and keeps its position.
        val photosVisible = tab == TAB_PHOTOS && collection == null
        Timeline(
            items = library.timeline,
            state = mainState,
            density = density,
            onDensityChange = viewModel::setDensity,
            selected = if (photosVisible) selected else emptySet(),
            onSelectionChange = viewModel::setSelection,
            onOpen = { openViewer(Source.Timeline, it) },
            header = {
                HomeHero(
                    library = library,
                    density = density,
                    onDensity = viewModel::setDensity,
                    seenStories = seenStories,
                    onOpenStory = { storyAt = it },
                    partialAccess = access == Access.Partial,
                    onManageAccess = { permissionLauncher.launch(MEDIA_PERMISSIONS) }
                )
            }
        )

        Fading(tab == TAB_ALBUMS && collection == null) {
            AlbumsScreen(
                library = library,
                onOpenAlbum = { collection = Source.AlbumRef(it.id) },
                onOpenPlace = { collection = Source.PlaceRef(it.id) },
                onOpenFavorites = { collection = Source.Favorites },
                onOpenVideos = { collection = Source.Videos },
                onOpenHidden = ::openHidden
            )
        }
        Fading(tab == TAB_MEMORIES && collection == null) {
            MemoriesScreen(
                library = library,
                seenStories = seenStories,
                onOpenStory = { storyAt = it },
                onOpenYear = { collection = Source.YearRef(it.year) },
                onOpenPlace = { collection = Source.PlaceRef(it.id) },
                alive = viewer == null && storyAt == null
            )
        }
        Fading(tab == TAB_SEARCH && collection == null) {
            SearchScreen(
                query = query,
                onQuery = viewModel::setQuery,
                ideas = remember(library.places) { library.places.take(4).map { it.name } + listOf("vídeo", "favorito", "captura") },
                results = results,
                state = searchState,
                selected = selected,
                onSelection = viewModel::setSelection,
                onOpen = { openViewer(Source.Search, it) }
            )
        }

        Fading(hiddenHome && collection == null) {
            HiddenScreen(
                library = library,
                onOpenAll = { collection = Source.Hidden },
                onOpenAlbum = { collection = Source.HiddenAlbum(it.id) },
                onBack = { hiddenHome = false }
            )
        }

        val open = collection
        Fading(open != null) {
            // Keeps showing the last list during the fade-out.
            val shown = remember { mutableStateOf<Source?>(null) }
            if (open != null) shown.value = open
            shown.value?.let { source ->
                CollectionScreen(
                    title = library.titleOf(source),
                    items = library.itemsOf(source, results),
                    state = collectionState,
                    density = density,
                    onDensity = viewModel::setDensity,
                    selected = selected,
                    onSelection = viewModel::setSelection,
                    onOpen = { openViewer(source, it) },
                    onBack = { collection = null },
                    action = {
                        val listed = library.itemsOf(source, results)
                        when (source) {
                            is Source.AlbumRef -> ApertureButton(Icons.Rounded.VisibilityOff, "Ocultar este álbum", {
                                viewModel.hide(listed.map { it.id })
                                collection = null
                            }, size = 44.dp)
                            is Source.HiddenAlbum, Source.Hidden -> ApertureButton(Icons.Rounded.Visibility, "Volver a mostrar todo", {
                                viewModel.unhide(listed.map { it.id })
                                collection = null
                            }, size = 44.dp)
                            else -> Unit
                        }
                    }
                )
            }
        }

        // Bottom: navigation, or the selection actions while selecting.
        val navOffset by animateFloatAsState(
            if (navShown || selected.isNotEmpty()) 0f else 1f,
            spring(dampingRatio = 0.9f, stiffness = 500f),
            label = "navOffset"
        )
        val navVisible = collection == null && !hiddenHome && selected.isEmpty()
        AnimatedVisibility(
            visible = navVisible,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.9f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
                .graphicsLayer { translationY = navOffset * 120.dp.toPx() }
        ) {
            FloatingNav(
                items = NAV_ITEMS,
                current = tab,
                onSelect = { route ->
                    if (route == tab && route == TAB_PHOTOS) scope.launch { mainState.list.animateScrollToItem(0) }
                    tab = route
                    navShown = true
                },
                primaryIcon = Icons.Rounded.CameraAlt,
                primaryLabel = "Abrir la cámara",
                onPrimary = {
                    runCatching { context.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
                        .onFailure { viewModel.showNotice("No hay ninguna app de cámara") }
                }
            )
        }
        AnimatedVisibility(
            visible = selected.isNotEmpty(),
            enter = fadeIn(tween(160)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(120)) + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
        ) {
            SelectionBar(
                count = selected.size,
                onClear = viewModel::clearSelection,
                onShare = { context.shareMedia(selectionItems) },
                onFavorite = {
                    viewModel.setFavorite(selected, selectionItems.any { !it.isFavorite })
                    viewModel.clearSelection()
                },
                onAlbum = { albumFor = selected.toList() },
                hiddenContext = isHiddenSource(activeSource),
                onHide = {
                    if (isHiddenSource(activeSource)) viewModel.unhide(selected) else viewModel.hide(selected)
                    viewModel.clearSelection()
                },
                onTrash = { trash(selectionItems) }
            )
        }

        // Keeps the clock and status icons readable over whatever scrolls beneath them.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(0f to Color(0xCC000000), 1f to Color(0x66000000)))
        )

        PreloadPill(Modifier.align(Alignment.TopCenter))

        // Viewer on top of everything except notices.
        viewer?.let { request ->
            val items = library.itemsOf(request.source, results)
            Viewer(
                items = items,
                startId = request.startId,
                originOf = { id -> activeState.rectOf(id) },
                onRevealInGrid = { id -> activeState.reveal(id) },
                actions = ViewerActions(
                    onShare = { context.shareMedia(listOf(it)) },
                    onFavorite = { viewModel.setFavorite(listOf(it.id), !it.isFavorite) },
                    onTrash = { trash(listOf(it)) },
                    onHideToggle = {
                        if (it.isHidden) viewModel.unhide(listOf(it.id)) else viewModel.hide(listOf(it.id))
                    },
                    onAlbum = { albumFor = listOf(it.id) },
                    onEdit = { editing = it }
                ),
                suspended = editing != null,
                onClose = { viewer = null }
            )
        }

        editing?.let { item ->
            Editor(
                item = item,
                onClose = { editing = null },
                onSaved = {
                    editing = null
                    viewModel.showNotice("Copia guardada junto a la original")
                },
                onError = viewModel::showNotice
            )
        }

        storyAt?.let { start ->
            if (library.stories.isNotEmpty()) {
                val favoriteIds = remember(library) { library.favorites.mapTo(HashSet()) { it.id } }
                StoryPlayer(
                    stories = library.stories,
                    startStory = start,
                    isFavorite = { it.id in favoriteIds },
                    onFavorite = { viewModel.setFavorite(listOf(it.id), it.id !in favoriteIds) },
                    onShare = { context.shareMedia(listOf(it)) },
                    onSeen = { viewModel.markStorySeen(it.id) },
                    onClose = { storyAt = null }
                )
            }
        }

        NoticeHost(notice, viewModel::dismissNotice, Modifier.align(Alignment.BottomCenter))
    }

    albumFor?.let { ids ->
        val custom = library.albums.filter { it.isCustom }.map { it.name }
        val inAlbum = library.timeline.any { it.id in ids && it.album != null }
        AlbumPickerSheet(
            existing = custom,
            onPick = { name, hidden ->
                if (hidden) viewModel.addToHiddenAlbum(ids, name) else viewModel.addToAlbum(ids, name)
                viewModel.clearSelection()
                albumFor = null
            },
            onRemove = if (inAlbum) ({
                viewModel.addToAlbum(ids, null)
                viewModel.clearSelection()
                albumFor = null
            }) else null,
            onDismiss = { albumFor = null }
        )
    }
}

private const val TAB_PHOTOS = "photos"
private const val TAB_MEMORIES = "memories"
private const val TAB_ALBUMS = "albums"
private const val TAB_SEARCH = "search"

private val NAV_ITEMS = listOf(
    NavItem(TAB_PHOTOS, "Fotos", Icons.Rounded.PhotoLibrary),
    NavItem(TAB_MEMORIES, "Recuerdos", Icons.Rounded.AutoAwesome),
    NavItem(TAB_ALBUMS, "Álbumes", Icons.Rounded.CollectionsBookmark),
    NavItem(TAB_SEARCH, "Buscar", Icons.Rounded.Search)
)

@Composable
private fun Fading(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.85f, stiffness = 500f), initialScale = 0.96f),
        exit = fadeOut(tween(130)) + scaleOut(tween(130), targetScale = 0.98f)
    ) { content() }
}

@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onAlbum: () -> Unit,
    hiddenContext: Boolean,
    onHide: () -> Unit,
    onTrash: () -> Unit
) {
    Row(
        Modifier
            .floatingShadow(PillShape, 24.dp)
            .clip(PillShape)
            .background(Darkroom.Nav)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            Modifier
                .bounceClick(onClick = onClear)
                .clip(PillShape)
                .background(Darkroom.Accent)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.Close, "Cancelar selección", tint = Darkroom.OnAccent, modifier = Modifier.size(18.dp))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = Darkroom.OnAccent)
        }
        BarAction(Icons.Rounded.IosShare, "Compartir", onClick = onShare)
        BarAction(Icons.Rounded.Favorite, "Favorita", onClick = onFavorite)
        BarAction(Icons.Rounded.CollectionsBookmark, "Añadir a álbum", onClick = onAlbum)
        if (hiddenContext) BarAction(Icons.Rounded.Visibility, "Volver a mostrar", onClick = onHide)
        else BarAction(Icons.Rounded.VisibilityOff, "Ocultar", onClick = onHide)
        BarAction(Icons.Rounded.DeleteOutline, "Eliminar", tint = Darkroom.Danger, onClick = onTrash)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, tint: Color = Darkroom.Ink, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .bounceClick(pressedScale = 0.84f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** First install only: quietly shows the thumbnail build; the app stays fully usable meanwhile. */
@Composable
private fun PreloadPill(modifier: Modifier = Modifier) {
    val progress by Thumbnails.progress.collectAsStateWithLifecycle()
    val shown = remember { mutableStateOf(progress) }
    progress?.let { shown.value = it }
    AnimatedVisibility(
        visible = progress != null,
        enter = fadeIn(tween(200)) + slideInVertically { -it },
        exit = fadeOut(tween(250)) + slideOutVertically { -it },
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp)
    ) {
        val p = shown.value ?: return@AnimatedVisibility
        Row(
            Modifier
                .floatingShadow(PillShape, 12.dp)
                .clip(PillShape)
                .background(Darkroom.Nav)
                .border(1.dp, Darkroom.Hairline, PillShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                progress = { p.done.toFloat() / p.total.coerceAtLeast(1) },
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Darkroom.Accent,
                trackColor = Darkroom.Hairline
            )
            Text(
                "Preparando miniaturas ${p.done}/${p.total}",
                style = MaterialTheme.typography.labelSmall,
                color = Darkroom.Ink
            )
        }
    }
}

@Composable
private fun NoticeHost(notice: Notice?, onDismiss: (Notice) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val last = remember { mutableStateOf(notice) }
    notice?.let { last.value = it }
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        haptics.confirm()
        delay(if (current.action != null) 5000 else 2500)
        onDismiss(current)
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(160)) + slideInVertically { it },
        exit = fadeOut(tween(160)) + slideOutVertically { it },
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 92.dp, start = 16.dp, end = 16.dp)
    ) {
        val n = last.value ?: return@AnimatedVisibility
        Row(
            Modifier
                .widthIn(max = 420.dp)
                .floatingShadow(PillShape, 16.dp)
                .clip(PillShape)
                .background(Darkroom.Ink)
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                n.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Darkroom.OnInk,
                modifier = Modifier.padding(vertical = 10.dp)
            )
            if (n.actionLabel != null) {
                Box(
                    Modifier
                        .bounceClick {
                            n.action?.invoke()
                            onDismiss(n)
                        }
                        .clip(PillShape)
                        .background(Darkroom.Accent)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(n.actionLabel, style = MaterialTheme.typography.labelLarge, color = Darkroom.OnAccent)
                }
            }
        }
    }
}

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private fun Context.shareMedia(items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val type = items.map { it.mimeType }.distinct().singleOrNull()
        ?: if (items.all { it.isVideo }) "video/*" else if (items.none { it.isVideo }) "image/*" else "*/*"
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    send.type = type
    send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null))
}
