package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.GalleryApplication
import com.example.data.GalleryDatabase
import com.example.data.Library
import com.example.data.PlaceIndexer
import com.example.data.MediaItem
import com.example.data.MediaSearch
import com.example.data.Thumbnails
import com.example.ui.timeline.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null, val id: Long = System.nanoTime())

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GalleryApplication>()
    private val repository = (application as GalleryApplication).repository
    private val prefs = application.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE)

    val library: StateFlow<Library> = repository.library()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Library.EMPTY)

    private val placeTrigger = MutableStateFlow(0)

    private val _density = MutableStateFlow(
        runCatching { Density.valueOf(prefs.getString("density", null) ?: "") }.getOrDefault(Density.Moments)
    )
    val density: StateFlow<Density> = _density.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<MediaItem>> = combine(
        library,
        _query.debounce { if (it.isEmpty()) 0L else 180L }.map { it.trim() }.distinctUntilChanged()
    ) { lib, q -> MediaSearch.filter(lib.timeline, q) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            library
                .filter { it.complete }
                .map { (it.timeline + it.hidden) }
                .distinctUntilChanged { a, b -> a.size == b.size && a.firstOrNull()?.id == b.firstOrNull()?.id }
                .collectLatest { Thumbnails.preload(app, it) }
        }
        viewModelScope.launch {
            combine(
                library.filter { it.complete }.map { it.timeline + it.hidden }
                    .distinctUntilChanged { a, b -> a.size == b.size && a.firstOrNull()?.id == b.firstOrNull()?.id },
                placeTrigger
            ) { items, _ -> items }
                .collectLatest { items ->
                    runCatching {
                        PlaceIndexer(app, GalleryDatabase.getDatabase(app)).index(items)
                    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
        }
    }

    fun onPermissionChanged() = repository.refresh()

    /** The system dialog for photo locations is shown once; afterwards it is the user's call in settings. */
    fun shouldAskPhotoLocation(): Boolean = !prefs.getBoolean("asked_photo_location", false)

    fun onPhotoLocationAnswered() {
        prefs.edit().putBoolean("asked_photo_location", true).apply()
        placeTrigger.value += 1
    }

    fun setDensity(density: Density) {
        _density.value = density
        prefs.edit().putString("density", density.name).apply()
    }

    fun setSelection(ids: Set<String>) {
        _selected.value = ids
    }

    fun clearSelection() = setSelection(emptySet())

    fun setQuery(query: String) {
        _query.value = query
    }

    fun showNotice(text: String) {
        _notice.value = Notice(text)
    }

    fun dismissNotice(notice: Notice) {
        if (_notice.value?.id == notice.id) _notice.value = null
    }

    fun setFavorite(ids: Collection<String>, favorite: Boolean) {
        viewModelScope.launch { repository.setFavorite(ids, favorite) }
    }

    fun hide(ids: Collection<String>) {
        val copy = ids.toList()
        viewModelScope.launch {
            repository.setHidden(copy, true)
            _notice.value = Notice(
                if (copy.size == 1) "Movida a Oculto" else "${copy.size} elementos en Oculto",
                actionLabel = "Deshacer",
                action = { unhide(copy, silent = true) }
            )
        }
    }

    fun unhide(ids: Collection<String>, silent: Boolean = false) {
        viewModelScope.launch {
            repository.setHidden(ids, false)
            if (!silent) _notice.value = Notice("Vuelve a estar visible")
        }
    }

    fun addToAlbum(ids: Collection<String>, album: String?) {
        viewModelScope.launch {
            repository.setAlbum(ids, album)
            _notice.value = Notice(if (album != null) "Añadida a $album" else "Quitada del álbum")
        }
    }

    /** Moves photos into a hidden album, which only shows inside Oculto. */
    fun addToHiddenAlbum(ids: Collection<String>, album: String) {
        val copy = ids.toList()
        viewModelScope.launch {
            repository.setAlbum(copy, album)
            repository.setHidden(copy, true)
            _notice.value = Notice("En el álbum oculto $album")
        }
    }

    private val seenKey get() = "seen_${java.time.LocalDate.now().toEpochDay()}"
    private val _seenStories = MutableStateFlow(prefs.getStringSet(seenKey, emptySet()).orEmpty().toSet())
    val seenStories: StateFlow<Set<String>> = _seenStories.asStateFlow()

    fun markStorySeen(id: String) {
        if (id in _seenStories.value) return
        _seenStories.value = _seenStories.value + id
        prefs.edit().putStringSet(seenKey, _seenStories.value).apply()
    }
}
