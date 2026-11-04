package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** Where a photo was taken. A row with no [cell] means it was read and has no GPS, so it is not read again. */
@Entity(tableName = "media_places")
data class MediaPlace(
    @PrimaryKey val mediaId: String,
    val cell: String? = null,
    val place: String? = null
)

data class PlaceName(val mediaId: String, val place: String)

@Dao
interface MediaPlaceDao {
    @Query("SELECT mediaId, place FROM media_places WHERE place IS NOT NULL AND place != ''")
    fun named(): Flow<List<PlaceName>>

    @Query("SELECT mediaId FROM media_places")
    suspend fun scannedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MediaPlace>)

    @Query("SELECT DISTINCT cell FROM media_places WHERE cell IS NOT NULL AND place IS NULL")
    suspend fun unnamedCells(): List<String>

    @Query("UPDATE media_places SET place = :place WHERE cell = :cell")
    suspend fun nameCell(cell: String, place: String)
}

/**
 * Reads GPS from photos in the background and turns it into town names. Coordinates are rounded to a cell of
 * about 5 km before the system geocoder sees them, and each cell is asked about only once.
 */
class PlaceIndexer(private val context: Context, private val database: GalleryDatabase) {
    private val dao = database.mediaPlaceDao()

    suspend fun index(items: List<MediaItem>) = withContext(Thumbnails.preloadDispatcher) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext
        }
        val scanned = dao.scannedIds().toHashSet()
        items.asSequence()
            .filter { !it.isVideo && !it.dateEstimated && it.id !in scanned }
            .chunked(200)
            .forEach { chunk -> dao.insertAll(chunk.map { MediaPlace(it.id, cell = cellOf(it)) }) }
        nameCells()
    }

    private fun cellOf(item: MediaItem): String? {
        if (item.isScreenshot) return null
        return try {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(item.uri))?.use { stream ->
                ExifInterface(stream).latLong?.let { (lat, lon) ->
                    if (lat == 0.0 && lon == 0.0) null else "${(lat * 20).roundToInt()}:${(lon * 20).roundToInt()}"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun nameCells() {
        if (!Geocoder.isPresent()) return
        val geocoder = Geocoder(context, Locale.forLanguageTag("es-ES"))
        // Written in small batches so names appear progressively without rebuilding the library for each one.
        dao.unnamedCells().chunked(12).forEach { cells ->
            val named = cells.mapNotNull { cell -> nameOf(geocoder, cell)?.let { cell to it } }
            database.withTransaction { named.forEach { (cell, place) -> dao.nameCell(cell, place) } }
        }
    }

    /** The town at a cell; "" when the place has no name; null when the lookup failed and should be retried later. */
    private suspend fun nameOf(geocoder: Geocoder, cell: String): String? {
        val (la, lo) = cell.split(':').map { it.toDouble() / 20.0 }
        return withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(la, lo, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        val a = addresses.firstOrNull()
                        if (cont.isActive) {
                            cont.resume(a?.let { it.locality ?: it.subAdminArea ?: it.adminArea ?: it.countryName } ?: "")
                        }
                    }

                    override fun onError(errorMessage: String?) {
                        Log.w("PlaceIndexer", "geocoder: $errorMessage")
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }
    }
}
