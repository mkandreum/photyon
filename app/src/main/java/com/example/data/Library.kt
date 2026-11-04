package com.example.data

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Immutable
data class Album(val id: String, val name: String, val items: List<MediaItem>, val isCustom: Boolean = false)

@Immutable
data class Memory(val yearsAgo: Int, val items: List<MediaItem>)

/** Everything the UI shows, computed once off the main thread per catalog change. */
@Immutable
class Library(
    val timeline: List<MediaItem>,
    val favorites: List<MediaItem>,
    val videos: List<MediaItem>,
    val hidden: List<MediaItem>,
    val albums: List<Album>,
    val memories: List<Memory>,
    /** False while only the newest page has been read (fast first frame). */
    val complete: Boolean
) {
    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)

        fun build(
            media: List<MediaItem>,
            overrides: Map<String, MediaOverride>,
            complete: Boolean,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault()
        ): Library {
            val merged = media.map { item ->
                val o = overrides[item.id] ?: return@map item
                item.copy(
                    isFavorite = o.isFavorite,
                    isHidden = o.isHidden,
                    album = o.customAlbum?.takeIf { it.isNotBlank() },
                    rotation = o.rotation,
                    brightness = o.brightness,
                    contrast = o.contrast,
                    saturation = o.saturation
                )
            }
            val visible = merged.filter { !it.isHidden }
            val buckets = visible.filter { it.bucketId.isNotEmpty() }.groupBy { it.bucketId }
                .map { (id, list) -> Album(id, list.first().bucketName.ifBlank { "Sin nombre" }, list) }
            val custom = visible.filter { it.album != null }.groupBy { it.album!! }
                .map { (name, list) -> Album("custom:$name", name, list, isCustom = true) }
            return Library(
                timeline = visible,
                favorites = visible.filter { it.isFavorite },
                videos = visible.filter { it.isVideo },
                hidden = merged.filter { it.isHidden },
                albums = (custom + buckets).sortedWith(
                    compareByDescending<Album> { it.isCustom }.thenByDescending { it.items.first().dateTakenMs }
                ),
                memories = memoriesOf(visible, today, zone),
                complete = complete
            )
        }

        /** Photos taken within three days of today's date in earlier years, newest year first. */
        fun memoriesOf(items: List<MediaItem>, today: LocalDate, zone: ZoneId): List<Memory> {
            val byYear = HashMap<Int, MutableList<MediaItem>>()
            for (item in items) {
                if (item.isVideo) continue
                val date = Instant.ofEpochMilli(item.dateTakenMs).atZone(zone).toLocalDate()
                if (date.year >= today.year) continue
                val distance = ChronoUnit.DAYS.between(today.withYear(date.year), date)
                if (distance in -3..3) byYear.getOrPut(today.year - date.year) { ArrayList() }.add(item)
            }
            return byYear.toSortedMap().map { (years, list) -> Memory(years, list) }
        }
    }
}

val MONTHS_ES = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
)

object MediaSearch {
    private val MARKS = "\\p{M}+".toRegex()

    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

    /** Every word must match the file name, album, month, year or a kind ("video", "favorito", "captura"). */
    fun filter(items: List<MediaItem>, query: String, zone: ZoneId = ZoneId.systemDefault()): List<MediaItem> {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return items.filter { item ->
            val date = Instant.ofEpochMilli(item.dateTakenMs).atZone(zone).toLocalDate()
            val text = normalize(
                buildString {
                    append(item.displayName).append(' ').append(item.bucketName).append(' ')
                    append(item.album.orEmpty()).append(' ')
                    append(MONTHS_ES[date.monthValue - 1]).append(' ').append(date.year).append(' ')
                    append(if (item.isVideo) "video" else "foto").append(' ')
                    if (item.isFavorite) append("favorito ")
                    if (item.bucketName.contains("screenshot", true) ||
                        item.bucketName.contains("captura", true)
                    ) append("captura ")
                }
            )
            tokens.all { it in text }
        }
    }
}
