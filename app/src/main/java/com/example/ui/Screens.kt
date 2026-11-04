package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Album
import com.example.data.Library
import com.example.data.MediaItem
import com.example.data.Memory
import com.example.ui.components.CardShape
import com.example.ui.components.ChoiceChip
import com.example.ui.components.ApertureButton
import com.example.ui.components.PillShape
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SheetShape
import com.example.ui.components.SoftButton
import com.example.ui.components.Wordmark
import com.example.ui.components.bounceClick
import com.example.ui.theme.Darkroom
import com.example.ui.timeline.Density
import com.example.ui.timeline.ThumbImage
import com.example.ui.timeline.Timeline
import com.example.ui.timeline.TimelineState

/* ---- Home hero ---- */

@Composable
fun HomeHero(
    library: Library,
    density: Density,
    onDensity: (Density) -> Unit,
    onOpenMemory: (Memory) -> Unit,
    partialAccess: Boolean,
    onManageAccess: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Wordmark()
            val countLabel = if (library.complete) "${library.timeline.size} RECUERDOS" else "LEYENDO TU GALERÍA…"
            Text(countLabel, style = MaterialTheme.typography.labelMedium, color = Darkroom.Muted)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(Density.entries.toList(), key = { it.name }) { d ->
                ChoiceChip(d.label, d == density, { onDensity(d) })
            }
        }
        if (partialAccess) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(Darkroom.AccentSoft)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Solo ves las fotos que elegiste.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Darkroom.Accent,
                    modifier = Modifier.weight(1f)
                )
                SoftButton("Gestionar", onManageAccess)
            }
        }
        if (library.memories.isNotEmpty() && density == Density.Moments) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(library.memories, key = { it.yearsAgo }) { memory ->
                    MemoryCard(memory) { onOpenMemory(memory) }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: Memory, onClick: () -> Unit) {
    Box(
        Modifier
            .size(148.dp, 196.dp)
            .bounceClick(pressedScale = 0.96f, onClick = onClick)
            .clip(CardShape)
            .background(Darkroom.Raised)
    ) {
        ThumbImage(memory.items.first(), 768, Modifier.fillMaxSize())
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(PillShape)
                .background(Darkroom.Accent)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                if (memory.yearsAgo == 1) "HACE 1 AÑO" else "HACE ${memory.yearsAgo} AÑOS",
                style = MaterialTheme.typography.labelSmall,
                color = Darkroom.OnAccent,
                maxLines = 1
            )
        }
    }
}

/* ---- Albums ---- */

@Composable
fun AlbumsScreen(
    library: Library,
    onOpenAlbum: (Album) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenHidden: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .background(Darkroom.Canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                Text("Álbumes", style = MaterialTheme.typography.headlineMedium, color = Darkroom.Ink)
                Text("${library.albums.size} COLECCIONES", style = MaterialTheme.typography.labelMedium, color = Darkroom.Muted)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "shortcuts") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortcutTile(Icons.Rounded.Favorite, "Favoritas", library.favorites.size, Modifier.weight(1f), onOpenFavorites)
                ShortcutTile(Icons.Rounded.Videocam, "Vídeos", library.videos.size, Modifier.weight(1f), onOpenVideos)
                ShortcutTile(Icons.Rounded.Lock, "Oculto", null, Modifier.weight(1f), onOpenHidden)
            }
        }
        items(library.albums, key = { it.id }, contentType = { "album" }) { album ->
            AlbumStack(album) { onOpenAlbum(album) }
        }
    }
}

@Composable
private fun ShortcutTile(icon: ImageVector, label: String, count: Int?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(92.dp)
            .bounceClick(pressedScale = 0.96f, onClick = onClick)
            .clip(CardShape)
            .background(Darkroom.Raised)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(icon, null, tint = Darkroom.Accent, modifier = Modifier.size(22.dp))
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Darkroom.Ink, maxLines = 1)
            if (count != null) Text("$count", style = MaterialTheme.typography.labelSmall, color = Darkroom.Muted)
        }
    }
}

/** An album as a physical pile: its three newest photos fanned out. */
@Composable
private fun AlbumStack(album: Album, onClick: () -> Unit) {
    Column(
        Modifier.bounceClick(pressedScale = 0.96f, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(8.dp)
        ) {
            val pile = album.items.take(3)
            pile.indices.reversed().forEach { i ->
                val turn = listOf(0f, 5f, -6f)[i]
                val shift = listOf(0f, 7f, -7f)[i]
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = turn
                            translationX = shift.dp.toPx()
                            alpha = if (i == 0) 1f else 0.72f
                            shape = RoundedCornerShape(12.dp)
                            clip = true
                        }
                        .background(Darkroom.Raised)
                ) { ThumbImage(pile[i], 0, Modifier.fillMaxSize()) }
            }
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(
                album.name,
                style = MaterialTheme.typography.titleSmall,
                color = Darkroom.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("${album.items.size}", style = MaterialTheme.typography.labelSmall, color = Darkroom.Muted)
        }
    }
}

/* ---- Collection (any album-like list) ---- */

@Composable
fun CollectionScreen(
    title: String,
    items: List<MediaItem>,
    state: TimelineState,
    density: Density,
    onDensity: (Density) -> Unit,
    selected: Set<String>,
    onSelection: (Set<String>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Darkroom.Canvas)
    ) {
        Timeline(
            items = items,
            state = state,
            density = density,
            onDensityChange = onDensity,
            selected = selected,
            onSelectionChange = onSelection,
            onOpen = onOpen,
            bottomPadding = 40.dp,
            header = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ApertureButton(Icons.Rounded.Close, "Cerrar", onBack, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, color = Darkroom.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${items.size} ELEMENTOS", style = MaterialTheme.typography.labelMedium, color = Darkroom.Muted)
                    }
                }
            }
        )
        if (items.isEmpty()) EmptyHint("Aquí no hay nada todavía.", Modifier.align(Alignment.Center))
    }
}

/* ---- Search ---- */

private val SEARCH_IDEAS = listOf("favorito", "video", "captura", "verano")

@Composable
fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<MediaItem>,
    state: TimelineState,
    selected: Set<String>,
    onSelection: (Set<String>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    Column(
        modifier
            .fillMaxSize()
            .background(Darkroom.Canvas)
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(PillShape)
                .background(Darkroom.Raised)
                .border(1.dp, Darkroom.Hairline, PillShape)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Search, null, tint = Darkroom.Muted, modifier = Modifier.size(22.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Marzo 2023, Camera, vídeo…", style = MaterialTheme.typography.bodyLarge, color = Darkroom.Muted, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Darkroom.Ink),
                    cursorBrush = SolidColor(Darkroom.Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .size(48.dp)
                        .bounceClick { onQuery("") },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Close, "Borrar búsqueda", tint = Darkroom.Muted, modifier = Modifier.size(20.dp)) }
            }
        }
        when {
            query.isBlank() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Prueba con", style = MaterialTheme.typography.titleMedium, color = Darkroom.Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SEARCH_IDEAS.take(3).forEach { ChoiceChip(it, false, { onQuery(it) }) }
                }
                Text(
                    "Busca por mes, año, álbum, nombre de archivo o tipo. Todo se queda en tu móvil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Darkroom.Muted
                )
            }
            results.isEmpty() -> EmptyHint("Sin resultados para “$query”.", Modifier.padding(32.dp))
            else -> Timeline(
                items = results,
                state = state,
                density = Density.Months,
                onDensityChange = {},
                selected = selected,
                onSelectionChange = onSelection,
                onOpen = onOpen,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/* ---- Permission gate ---- */

@Composable
fun PermissionScreen(denied: Boolean, onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Darkroom.Canvas)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
    ) {
        Wordmark()
        Text(
            "Tus fotos, ordenadas por lo que viviste.",
            style = MaterialTheme.typography.headlineSmall,
            color = Darkroom.Ink
        )
        Text(
            if (denied) "Para mostrarte la galería, activa el acceso a fotos y vídeos en los ajustes del móvil."
            else "Necesitamos acceso a tus fotos y vídeos para mostrarlos. Nada sale de tu móvil.",
            style = MaterialTheme.typography.bodyMedium,
            color = Darkroom.Muted
        )
        Spacer(Modifier.height(8.dp))
        if (denied) PrimaryButton("Abrir ajustes", onOpenSettings, leadingIcon = Icons.Rounded.PhotoLibrary)
        else PrimaryButton("Dar acceso", onGrant, leadingIcon = Icons.Rounded.PhotoLibrary)
    }
}

/* ---- Shared bits ---- */

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Darkroom.Muted, modifier = modifier)
}

/** Pick an album or create one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPickerSheet(
    existing: List<String>,
    onPick: (String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Darkroom.Sheet, shape = SheetShape) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Añadir a álbum", style = MaterialTheme.typography.headlineSmall, color = Darkroom.Ink)
            if (existing.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(existing, key = { it }) { ChoiceChip(it, false, { onPick(it) }) }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Darkroom.Raised)
                    .padding(16.dp)
            ) {
                if (name.isEmpty()) Text("Nombre del álbum nuevo", style = MaterialTheme.typography.bodyLarge, color = Darkroom.Muted)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Darkroom.Ink),
                    cursorBrush = SolidColor(Darkroom.Accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            PrimaryButton(
                "Crear y añadir",
                { onPick(name.trim()) },
                enabled = name.isNotBlank(),
                leadingIcon = Icons.Rounded.Add
            )
            if (onRemove != null) SoftButton("Quitar del álbum", onRemove, Modifier.fillMaxWidth(), danger = true)
        }
    }
}
