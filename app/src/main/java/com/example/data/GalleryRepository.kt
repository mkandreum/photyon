package com.example.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class GalleryRepository(
    private val context: Context,
    private val mediaOverrideDao: MediaOverrideDao,
    private val cachedMediaDao: CachedMediaDao
) {
    private fun hasReadPermission(): Boolean {
        val hasImages = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_MEDIA_IMAGES
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val hasVideos = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_MEDIA_VIDEO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val hasPartial = if (android.os.Build.VERSION.SDK_INT >= 34) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        return hasImages || hasVideos || hasPartial
    }

    private class Columns(val uri: android.net.Uri, val isVideo: Boolean)

    @Suppress("DEPRECATION")
    private suspend fun fetchDeviceMedia(): List<MediaItem>? {
        if (!hasReadPermission()) {
            Log.w("GalleryRepo", "Sync bypassed: Read media permissions are not granted yet.")
            return null
        }
        val items = ArrayList<MediaItem>()
        // Image and video MediaStore ids overlap, so videos get a prefix to keep ids unique.
        queryMedia(Columns(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false), items)
        queryMedia(Columns(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true), items)
        items.sortByDescending { it.dateAdded }
        return items
    }

    @Suppress("DEPRECATION")
    private suspend fun queryMedia(src: Columns, out: MutableList<MediaItem>) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            if (src.isVideo) add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Images.Media.LATITUDE)
            add(MediaStore.Images.Media.LONGITUDE)
        }.toTypedArray()
        try {
            context.contentResolver.query(
                src.uri, projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val wIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val hIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val durIdx = if (src.isVideo) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
                val latIdx = cursor.getColumnIndex(MediaStore.Images.Media.LATITUDE)
                val lonIdx = cursor.getColumnIndex(MediaStore.Images.Media.LONGITUDE)
                if (idIdx == -1 || nameIdx == -1) return@use

                var n = 0
                while (cursor.moveToNext()) {
                    // Allow cancellation (a newer sync supersedes this one) on big libraries
                    if (++n and 0xFF == 0) currentCoroutineContext().ensureActive()
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    out.add(
                        MediaItem(
                            id = if (src.isVideo) "v$id" else id.toString(),
                            uri = ContentUris.withAppendedId(src.uri, id).toString(),
                            displayName = name,
                            dateAdded = if (dateIdx != -1) cursor.getLong(dateIdx) else 0L,
                            size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L,
                            width = if (wIdx != -1) cursor.getInt(wIdx) else 0,
                            height = if (hIdx != -1) cursor.getInt(hIdx) else 0,
                            duration = if (durIdx != -1) cursor.getLong(durIdx) else null,
                            location = readLocation(latIdx, lonIdx, cursor),
                            isVideo = src.isVideo
                        )
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GalleryRepo", "Error fetching ${if (src.isVideo) "videos" else "images"} from MediaStore", e)
        }
    }

    private fun readLocation(
        latIdx: Int, lonIdx: Int, cursor: android.database.Cursor
    ): String? {
        if (latIdx == -1 || lonIdx == -1) return null
        val lat = cursor.getDouble(latIdx)
        val lon = cursor.getDouble(lonIdx)
        if (lat == 0.0 && lon == 0.0) return null
        return String.format(Locale.US, "%.4f, %.4f", lat, lon)
    }

    private val refreshRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Forces a MediaStore re-scan (e.g. right after the user grants media permissions). */
    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun startSync(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val contentFlow = callbackFlow {
                val handler = Handler(Looper.getMainLooper())
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
                try {
                    context.contentResolver.registerContentObserver(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
                    )
                    context.contentResolver.registerContentObserver(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
                    )
                } catch (e: SecurityException) {
                    Log.e("GalleryRepo", "SecurityException registering content observers", e)
                }
                trySend(Unit)
                awaitClose {
                    try {
                        context.contentResolver.unregisterContentObserver(observer)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            merge(contentFlow, refreshRequests)
                .debounce(150L)
                .collectLatest {
                    try {
                        val deviceMedia = fetchDeviceMedia() ?: return@collectLatest
                        val cached = deviceMedia.map {
                            CachedMediaItem(
                                id = it.id,
                                uri = it.uri,
                                displayName = it.displayName,
                                dateAdded = it.dateAdded,
                                duration = it.duration,
                                width = it.width,
                                height = it.height,
                                size = it.size,
                                location = it.location,
                                isVideo = it.isVideo
                            )
                        }
                        cachedMediaDao.syncMedia(cached)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("GalleryRepo", "Error syncing MediaStore with Room", e)
                    }
                }
        }
    }

    fun getMediaFlow(): Flow<List<MediaItem>> {
        return combine(cachedMediaDao.getAllCachedMedia(), mediaOverrideDao.getAllOverrides()) { dbItems, overrides ->
            val overridesMap = overrides.associateBy { it.mediaId }
            val mergedList = ArrayList<MediaItem>(dbItems.size + 8)
            val dbById = dbItems.associateBy { it.id }

            dbItems.forEach { dbItem ->
                val override = overridesMap[dbItem.id]
                mergedList.add(
                    MediaItem(
                        id = dbItem.id,
                        uri = dbItem.uri,
                        displayName = override?.customTitle ?: dbItem.displayName,
                        location = override?.customLocation ?: dbItem.location,
                        dateAdded = (override?.customDate ?: (dbItem.dateAdded * 1000)) / 1000,
                        duration = dbItem.duration,
                        width = dbItem.width,
                        height = dbItem.height,
                        size = dbItem.size,
                        isVideo = dbItem.isVideo,
                        isFavorite = override?.isFavorite ?: false,
                        isHidden = override?.isHidden ?: false,
                        customAlbum = override?.customAlbum,
                        rotation = override?.rotation ?: 0f,
                        brightness = override?.brightness ?: 0f,
                        contrast = override?.contrast ?: 1f,
                        saturation = override?.saturation ?: 1f
                    )
                )
            }

            overrides.forEach { override ->
                if (override.mediaId !in dbById) {
                    val baseId = override.mediaId.substringBefore("_duplicate_")
                    val baseItem = dbById[baseId]
                    if (baseItem != null) {
                        mergedList.add(
                            MediaItem(
                                id = override.mediaId,
                                uri = baseItem.uri,
                                displayName = override.customTitle
                                    ?: ("Copia de " + baseItem.displayName),
                                location = override.customLocation ?: baseItem.location,
                                dateAdded = (override.customDate ?: System.currentTimeMillis()) / 1000,
                                duration = baseItem.duration,
                                width = baseItem.width,
                                height = baseItem.height,
                                size = baseItem.size,
                                isVideo = baseItem.isVideo,
                                isFavorite = override.isFavorite,
                                isHidden = override.isHidden,
                                customAlbum = override.customAlbum,
                                rotation = override.rotation,
                                brightness = override.brightness,
                                contrast = override.contrast,
                                saturation = override.saturation
                            )
                        )
                    }
                }
            }

            mergedList.sortedByDescending { it.dateAdded }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun updateAdjustments(
        mediaId: String, rotation: Float, brightness: Float, contrast: Float, saturation: Float
    ) = withContext(Dispatchers.IO) {
        val existing = mediaOverrideDao.getOverrideById(mediaId)
        val updated = existing?.copy(
            rotation = rotation,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation
        ) ?: MediaOverride(
            mediaId = mediaId,
            rotation = rotation,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation
        )
        mediaOverrideDao.insertOverride(updated)
    }

    /** Flips the stored favorite flag (DB is the source of truth) and returns the new value. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun toggleFavorite(mediaId: String, currentFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val existing = mediaOverrideDao.getOverrideById(mediaId)
        val newValue = !(existing?.isFavorite ?: false)
        mediaOverrideDao.insertOverride(
            existing?.copy(isFavorite = newValue) ?: MediaOverride(mediaId = mediaId, isFavorite = newValue)
        )
        newValue
    }

    suspend fun setFavorite(mediaId: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        val existing = mediaOverrideDao.getOverrideById(mediaId)
        mediaOverrideDao.insertOverride(
            existing?.copy(isFavorite = favorite) ?: MediaOverride(mediaId = mediaId, isFavorite = favorite)
        )
    }

    suspend fun setHidden(mediaId: String, isHidden: Boolean) = withContext(Dispatchers.IO) {
        val existing = mediaOverrideDao.getOverrideById(mediaId)
        val updated = existing?.copy(isHidden = isHidden)
            ?: MediaOverride(mediaId = mediaId, isHidden = isHidden)
        mediaOverrideDao.insertOverride(updated)
    }

    suspend fun adjustMetadata(
        mediaId: String, newTitle: String?, newLocation: String?, newDateMs: Long?
    ) = withContext(Dispatchers.IO) {
        val existing = mediaOverrideDao.getOverrideById(mediaId)
        val updated = existing?.copy(
            customTitle = newTitle, customLocation = newLocation, customDate = newDateMs
        ) ?: MediaOverride(
            mediaId = mediaId,
            customTitle = newTitle,
            customLocation = newLocation,
            customDate = newDateMs
        )
        mediaOverrideDao.insertOverride(updated)
    }

    suspend fun updateCustomAlbum(mediaId: String, albumName: String?) = withContext(Dispatchers.IO) {
        val existing = mediaOverrideDao.getOverrideById(mediaId)
        val updated = existing?.copy(customAlbum = albumName)
            ?: MediaOverride(mediaId = mediaId, customAlbum = albumName)
        mediaOverrideDao.insertOverride(updated)
    }

    suspend fun createDuplicate(item: MediaItem) = withContext(Dispatchers.IO) {
        val duplicateId = "${item.id}_duplicate_${System.currentTimeMillis()}"
        val override = MediaOverride(
            mediaId = duplicateId,
            customTitle = "Duplicado - ${item.displayName}",
            customLocation = item.location,
            customDate = System.currentTimeMillis(),
            isFavorite = item.isFavorite,
            customAlbum = item.customAlbum
        )
        mediaOverrideDao.insertOverride(override)
    }
}
