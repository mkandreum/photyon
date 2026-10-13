package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedMediaDao {
    @Query("SELECT * FROM cached_media_items ORDER BY dateAdded DESC")
    fun getAllCachedMedia(): Flow<List<CachedMediaItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedMediaItem>)

    @Query("DELETE FROM cached_media_items WHERE id NOT IN (:ids)")
    suspend fun deleteOldItems(ids: List<String>)

    @Query("SELECT * FROM cached_media_items")
    suspend fun getAllOnce(): List<CachedMediaItem>

    @Query("DELETE FROM cached_media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Writes only the diff so unchanged syncs do not invalidate the catalog flow; chunked to respect SQLite's variable limit. */
    @Transaction
    suspend fun syncMedia(items: List<CachedMediaItem>) {
        val existing = getAllOnce().associateBy { it.id }
        val changed = items.filter { existing[it.id] != it }
        if (changed.isNotEmpty()) changed.chunked(500).forEach { insertAll(it) }
        val fresh = HashSet<String>(items.size * 2).apply { items.forEach { add(it.id) } }
        existing.keys.filter { it !in fresh }.chunked(500).forEach { deleteByIds(it) }
    }

    @Query("DELETE FROM cached_media_items")
    suspend fun clearAll()
}
