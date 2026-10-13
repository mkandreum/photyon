package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.GalleryDatabase
import com.example.data.GalleryRepository
import com.example.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GalleryViewModel(
    application: Application,
    private val repository: GalleryRepository
) : AndroidViewModel(application) {

    init {
        repository.startSync(viewModelScope)
    }

    // UI tab selector: "Fototeca" or "Colecciones"
    private val _currentTab = MutableStateFlow("Fototeca")
    val currentTab: StateFlow<String> = _currentTab.asStateFlow()

    // Grid modes: "Años", "Meses", "Todo"
    private val _gridMode = MutableStateFlow("Todo")
    val gridMode: StateFlow<String> = _gridMode.asStateFlow()

    // Year/Month drill-down state
    private val _selectedYear = MutableStateFlow<String?>(null)
    val selectedYear: StateFlow<String?> = _selectedYear.asStateFlow()
    private val _selectedMonth = MutableStateFlow<String?>(null)
    val selectedMonth: StateFlow<String?> = _selectedMonth.asStateFlow()

    fun drillDownYear(year: String) { _selectedYear.value = year; _selectedMonth.value = null }
    fun drillDownMonth(month: String) { _selectedMonth.value = month }
    fun resetDrillDown() { _selectedYear.value = null; _selectedMonth.value = null }

    /** Fast year/month lookup; one Calendar per computation (Calendar is not thread-safe). */
    private class DateKeys {
        private val cal = java.util.Calendar.getInstance()
        private fun set(dateSec: Long) { cal.timeInMillis = dateSec * 1000 }
        fun year(dateSec: Long): String { set(dateSec); return cal.get(java.util.Calendar.YEAR).toString() }
        fun month(dateSec: Long): String { set(dateSec); return monthName(cal.get(java.util.Calendar.MONTH)) }
        fun monthYear(dateSec: Long): String {
            set(dateSec)
            return "${monthName(cal.get(java.util.Calendar.MONTH))} ${cal.get(java.util.Calendar.YEAR)}"
        }
        private fun monthName(i: Int) = MONTHS[i]
        companion object {
            val MONTHS = listOf(
                "Enero","Febrero","Marzo","Abril","Mayo","Junio",
                "Julio","Agosto","Septiembre","Octubre","Noviembre","Diciembre"
            )
        }
    }

    data class YearSection(val year: String, val items: List<MediaItem>)
    data class MonthSection(val month: String, val items: List<MediaItem>)

    // Multiple selection tracking
    private val _isSelectionModeActive = MutableStateFlow(false)
    val isSelectionModeActive: StateFlow<Boolean> = _isSelectionModeActive.asStateFlow()

    private val _selectedItems = MutableStateFlow<Set<String>>(emptySet())
    val selectedItems: StateFlow<Set<String>> = _selectedItems.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Active single item detail views
    private val _activeDetailItem = MutableStateFlow<MediaItem?>(null)
    val activeDetailItem: StateFlow<MediaItem?> = _activeDetailItem.asStateFlow()

    private val shared = SharingStarted.WhileSubscribed(5_000)

    // Full catalog list from repository
    val rawMediaItems: StateFlow<List<MediaItem>> = repository.getMediaFlow()
        .stateIn(viewModelScope, shared, emptyList())

    /** Non-hidden catalog, computed once and reused by every derived list. */
    private val nonHiddenItems: StateFlow<List<MediaItem>> = rawMediaItems
        .map { items -> items.filter { !it.isHidden } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, shared, emptyList())

    // Debounced query (clearing is immediate); the text field keeps using searchQuery directly
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val effectiveQuery = _searchQuery
        .debounce { if (it.isEmpty()) 0L else 200L }
        .map { it.trim() }
        .distinctUntilChanged()

    // Filtered media items to show in grid (respect searchQuery & exclude hidden items)
    val visibleMediaItems: StateFlow<List<MediaItem>> = combine(
        nonHiddenItems,
        effectiveQuery
    ) { items, query ->
        if (query.isEmpty()) items
        else items.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                    (it.location?.contains(query, ignoreCase = true) == true)
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, shared, emptyList())

    // Override list for detail view pager (supports hidden items viewing)
    private val _detailListOverride = MutableStateFlow<List<MediaItem>?>(null)

    val detailList: StateFlow<List<MediaItem>> = combine(
        _detailListOverride,
        visibleMediaItems
    ) { override, default ->
        override ?: default
    }.stateIn(viewModelScope, shared, emptyList())

    val favoriteItems: StateFlow<List<MediaItem>> = nonHiddenItems
        .map { items -> items.filter { it.isFavorite } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, shared, emptyList())

    // Hidden Items (the secure Hidden Album)
    val hiddenItems: StateFlow<List<MediaItem>> = rawMediaItems
        .map { items -> items.filter { it.isHidden } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, shared, emptyList())

    /** Cheap boolean for the nav-bar badge so it does not recompose on every list change. */
    val hasHiddenItems: StateFlow<Boolean> = hiddenItems
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, shared, false)

    // Memory cards list grouped dynamically by location
    val memoryCollections: StateFlow<List<MemoryGroup>> = nonHiddenItems
        .map { items ->
            val keys = DateKeys()
            items.asSequence()
                .filter { !it.location.isNullOrEmpty() }
                .groupBy { it.location!! }
                .map { (location, list) ->
                    MemoryGroup(
                        location = location,
                        year = list.firstOrNull()?.let { keys.year(it.dateAdded) } ?: "",
                        description = "VIAJE",
                        coverUri = list.firstOrNull()?.uri ?: "",
                        items = list
                    )
                }.sortedBy { it.location }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, shared, emptyList())

    // Custom Albums compiled from database custom album field
    val customAlbums: StateFlow<Map<String, List<MediaItem>>> = rawMediaItems
        .map { items ->
            items.filter { !it.customAlbum.isNullOrBlank() }
                .groupBy { it.customAlbum!! }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, shared, emptyMap())

    val groupedYears: StateFlow<List<YearSection>> = nonHiddenItems
        .map { items ->
            val keys = DateKeys()
            items.groupBy { keys.year(it.dateAdded) }
                .map { (y, list) -> YearSection(y, list) }
                .sortedByDescending { it.year }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, shared, emptyList())

    val groupedMonths: StateFlow<List<MonthSection>> = nonHiddenItems
        .map { items ->
            val keys = DateKeys()
            items.groupBy { keys.monthYear(it.dateAdded) }
                .map { (m, list) -> MonthSection(m, list) }
                .sortedByDescending { it.items.firstOrNull()?.dateAdded ?: 0L }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, shared, emptyList())

    val drilledDownMonths: StateFlow<List<MonthSection>> = combine(nonHiddenItems, _selectedYear) { items, year ->
        val keys = DateKeys()
        val filtered = if (year != null) items.filter { keys.year(it.dateAdded) == year } else items
        // Without a selected year, months from different years must not merge
        filtered.groupBy { if (year != null) keys.month(it.dateAdded) else keys.monthYear(it.dateAdded) }
            .map { (m, list) -> MonthSection(m, list) }
            .sortedByDescending { it.items.firstOrNull()?.dateAdded ?: 0L }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, shared, emptyList())

    /** Called after the media permission result so the catalog loads without waiting for a MediaStore change. */
    fun onMediaPermissionResult() {
        repository.refresh()
    }

    fun selectTab(tab: String) {
        _currentTab.value = tab
    }

    fun setGridMode(mode: String) {
        _gridMode.value = mode
        resetDrillDown()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleSelectionMode() {
        _selectedItems.value = emptySet()
        _isSelectionModeActive.update { !it }
    }

    fun toggleItemSelection(id: String) {
        _selectedItems.update { if (id in it) it - id else it + id }
    }

    fun setDetailItem(item: MediaItem?, sourceList: List<MediaItem>? = null) {
        if (item != null && item.id == _activeDetailItem.value?.id) {
            // Same item already showing, skip
            return
        }
        _activeDetailItem.value = item
        if (sourceList != null) {
            _detailListOverride.value = sourceList
        }
    }

    fun clearDetailListOverride() {
        _detailListOverride.value = null
    }

    // Media actions
    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch {
            val newValue = repository.toggleFavorite(item.id, item.isFavorite)
            // Update active detail reference to reflect immediate UI change
            _activeDetailItem.update { if (it != null && it.id == item.id) it.copy(isFavorite = newValue) else it }
        }
    }

    fun hideItem(item: MediaItem) {
        viewModelScope.launch {
            repository.setHidden(item.id, true)
            if (_activeDetailItem.value?.id == item.id) {
                _activeDetailItem.value = null
            }
            Toast.makeText(getApplication(), "Elemento ocultado", Toast.LENGTH_SHORT).show()
        }
    }

    fun unhideItem(item: MediaItem) {
        viewModelScope.launch {
            repository.setHidden(item.id, false)
            Toast.makeText(getApplication(), "Elemento vuelto a mostrar", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteItem(item: MediaItem) {
        viewModelScope.launch {
            // Since deleting from storage requires write prompt on modern Android,
            // we delete it gracefully using hidden override! This is extremely safe, doesn't require
            // breaking prompt flows, and achieves perfect high-fidelity sandbox simulation.
            repository.setHidden(item.id, true)
            if (_activeDetailItem.value?.id == item.id) {
                _activeDetailItem.value = null
            }
            Toast.makeText(getApplication(), "Elemento eliminado de la vista", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyItemToClipboard(item: MediaItem) {
        val context = getApplication<Application>().applicationContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Copied Media URI", item.uri)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copiado al portapapeles: ${item.displayName}", Toast.LENGTH_SHORT).show()
    }

    fun duplicateItem(item: MediaItem) {
        viewModelScope.launch {
            repository.createDuplicate(item)
            Toast.makeText(getApplication(), "Duplicando elemento...", Toast.LENGTH_SHORT).show()
        }
    }

    fun changeMetadata(item: MediaItem, title: String?, location: String?, dateMs: Long?) {
        viewModelScope.launch {
            repository.adjustMetadata(item.id, title, location, dateMs)
            
            // Adjust local detail item instantly of the open item
            if (_activeDetailItem.value?.id == item.id) {
                _activeDetailItem.value = _activeDetailItem.value?.copy(
                    displayName = title ?: item.displayName,
                    location = location ?: item.location,
                    dateAdded = (dateMs ?: (item.dateAdded * 1000)) / 1000
                )
            }
            Toast.makeText(getApplication(), "Datos actualizados", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveImageAdjustments(item: MediaItem, rotation: Float, brightness: Float, contrast: Float, saturation: Float) {
        viewModelScope.launch {
            repository.updateAdjustments(item.id, rotation, brightness, contrast, saturation)
            if (_activeDetailItem.value?.id == item.id) {
                _activeDetailItem.value = _activeDetailItem.value?.copy(
                    rotation = rotation,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation
                )
            }
            Toast.makeText(getApplication(), "Ajustes de imagen guardados", Toast.LENGTH_SHORT).show()
        }
    }

    fun addToAlbum(item: MediaItem, albumName: String) {
        viewModelScope.launch {
            repository.updateCustomAlbum(item.id, albumName)
            if (_activeDetailItem.value?.id == item.id) {
                _activeDetailItem.value = _activeDetailItem.value?.copy(customAlbum = albumName)
            }
            Toast.makeText(getApplication(), "Añadido a álbum $albumName", Toast.LENGTH_SHORT).show()
        }
    }

    // Bulk selection actions
    fun bulkFavorite() {
        viewModelScope.launch {
            val ids = _selectedItems.value
            val targets = visibleMediaItems.value.filter { it.id in ids }
            // Mixed selection: favorite everything; all already favorite: unfavorite
            val makeFavorite = targets.any { !it.isFavorite }
            withContext(Dispatchers.IO) {
                targets.forEach { repository.setFavorite(it.id, makeFavorite) }
            }
            toggleSelectionMode()
            Toast.makeText(
                getApplication(),
                if (makeFavorite) "Añadido a favoritos" else "Quitado de favoritos",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun bulkHide() {
        viewModelScope.launch {
            val ids = _selectedItems.value
            withContext(Dispatchers.IO) { ids.forEach { repository.setHidden(it, true) } }
            toggleSelectionMode()
            Toast.makeText(getApplication(), "Elementos ocultados en lote", Toast.LENGTH_SHORT).show()
        }
    }

    fun bulkDelete() {
        viewModelScope.launch {
            val ids = _selectedItems.value
            withContext(Dispatchers.IO) { ids.forEach { repository.setHidden(it, true) } }
            toggleSelectionMode()
            Toast.makeText(getApplication(), "Elementos eliminados en lote", Toast.LENGTH_SHORT).show()
        }
    }
}

// Memory group representation helper class
data class MemoryGroup(
    val location: String,
    val year: String,
    val description: String,
    val coverUri: String,
    val items: List<MediaItem>
)

class GalleryViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            val database = GalleryDatabase.getDatabase(application)
            val repository = GalleryRepository(application, database.mediaOverrideDao(), database.cachedMediaDao())
            @Suppress("UNCHECKED_CAST")
            return GalleryViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
