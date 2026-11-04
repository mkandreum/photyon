package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/** MediaStore is the catalog's source of truth; Room only stores what the user added on top. */
class GalleryRepository(
    private val context: Context,
    private val database: GalleryDatabase
) {
    private val overridesDao = database.mediaOverrideDao()

    private class Snapshot(val items: List<MediaItem>, val complete: Boolean)

    private val refreshRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Forces a re-scan, e.g. right after the user grants access. */
    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    private fun mediaStoreChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /** Emits the newest page right away for an instant first frame, then the whole catalog on every change. */
    @OptIn(FlowPreview::class)
    private fun snapshots(): Flow<Snapshot> = channelFlow {
        send(Snapshot(readAll(limit = FIRST_PAGE), complete = false))
        merge(mediaStoreChanges(), refreshRequests)
            .debounce(250L)
            .collectLatest { send(Snapshot(readAll(limit = null), complete = true)) }
    }.flowOn(Dispatchers.IO)

    fun library(): Flow<Library> =
        combine(
            snapshots(),
            overridesDao.getAllOverrides(),
            database.mediaPlaceDao().named().distinctUntilChanged()
        ) { snapshot, overrides, places ->
            Library.build(
                snapshot.items,
                overrides.associateBy { it.mediaId },
                snapshot.complete,
                places = places.associate { it.mediaId to it.place }
            )
        }.flowOn(Dispatchers.Default)

    private suspend fun readAll(limit: Int?): List<MediaItem> = coroutineScope {
        val images = async { query(isVideo = false, limit) }
        val videos = async { query(isVideo = true, limit) }
        val merged = images.await() + videos.await()
        val sorted = merged.sortedByDescending { it.dateTakenMs }
        if (limit != null) sorted.take(limit) else sorted
    }

    @Suppress("DEPRECATION")
    private suspend fun query(isVideo: Boolean, limit: Int?): List<MediaItem> {
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.ORIENTATION,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION
        )
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = ArrayList<MediaItem>(limit ?: 4096)
        val now = System.currentTimeMillis()
        try {
            context.contentResolver.query(collection, projection, args, null)?.use { c ->
                var n = 0
                while (c.moveToNext()) {
                    if (++n and 0xFF == 0) currentCoroutineContext().ensureActive()
                    val id = c.getLong(0)
                    val name = c.getString(1).orEmpty()
                    val taken = c.getLong(2)
                    val fromName = if (taken > 0) null else MediaDates.fromFileName(name, now)
                    val added = c.getLong(3) * 1000
                    val modified = c.getLong(4) * 1000
                    val orientation = c.getInt(8)
                    val w = c.getInt(6)
                    val h = c.getInt(7)
                    val swap = orientation == 90 || orientation == 270
                    out.add(
                        MediaItem(
                            id = if (isVideo) "v$id" else id.toString(),
                            mediaId = id,
                            isVideo = isVideo,
                            displayName = name,
                            dateTakenMs = when {
                                taken > 0 -> taken
                                fromName != null -> fromName
                                // A restored file keeps its older modification date; closer to the truth than the import.
                                modified in 1 until added -> modified
                                else -> added
                            },
                            dateEstimated = taken <= 0 && fromName == null,
                            dateModifiedSec = c.getLong(4),
                            sizeBytes = c.getLong(5),
                            width = if (swap) h else w,
                            height = if (swap) w else h,
                            mimeType = c.getString(9).orEmpty(),
                            bucketId = c.getString(10).orEmpty(),
                            bucketName = c.getString(11).orEmpty(),
                            durationMs = c.getLong(12)
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Also the normal path before the permission is granted.
            Log.w("GalleryRepo", "MediaStore query failed: ${e.message}")
        }
        return out
    }

    private suspend fun edit(ids: Collection<String>, change: (MediaOverride) -> MediaOverride) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                ids.forEach { id ->
                    overridesDao.insertOverride(change(overridesDao.getOverrideById(id) ?: MediaOverride(mediaId = id)))
                }
            }
        }

    suspend fun setFavorite(ids: Collection<String>, favorite: Boolean) = edit(ids) { it.copy(isFavorite = favorite) }

    suspend fun setHidden(ids: Collection<String>, hidden: Boolean) = edit(ids) { it.copy(isHidden = hidden) }

    suspend fun setAlbum(ids: Collection<String>, album: String?) = edit(ids) { it.copy(customAlbum = album) }

    private companion object {
        const val FIRST_PAGE = 120
    }
}
