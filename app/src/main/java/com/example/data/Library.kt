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

/** A short full-screen slideshow. */
@Immutable
data class Story(val id: String, val title: String, val subtitle: String, val items: List<MediaItem>)

/** When a story's photos were taken: "4 NOV 2024", "2-6 NOV 2024", "MAR-NOV 2024" or "2019-2024". */
fun Story.dateLabel(zone: ZoneId = ZoneId.systemDefault()): String {
    if (items.all { it.dateEstimated }) return "SIN FECHA EXACTA"
    val dates = items.filter { !it.dateEstimated }.map { Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate() }
    val first = dates.min()
    val last = dates.max()
    fun month(d: LocalDate) = MONTHS_ES[d.monthValue - 1].take(3).uppercase()
    return when {
        first == last -> "${first.dayOfMonth} ${month(first)} ${first.year}"
        first.year != last.year -> "${first.year}-${last.year}"
        first.monthValue != last.monthValue -> "${month(first)}-${month(last)} ${first.year}"
        else -> "${first.dayOfMonth}-${last.dayOfMonth} ${month(first)} ${first.year}"
    }
}

@Immutable
data class YearStat(val year: Int, val items: List<MediaItem>)

@Immutable
data class BusiestDay(val date: LocalDate, val count: Int)

/** Everything the UI shows, computed once off the main thread per catalog change. */
@Immutable
class Library(
    val timeline: List<MediaItem>,
    val favorites: List<MediaItem>,
    val videos: List<MediaItem>,
    val hidden: List<MediaItem>,
    val albums: List<Album>,
    val memories: List<Memory>,
    val hiddenAlbums: List<Album>,
    val places: List<Album>,
    val stories: List<Story>,
    val years: List<YearStat>,
    val busiestDay: BusiestDay?,
    /** False while only the newest page has been read (fast first frame). */
    val complete: Boolean
) {
    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, false)

        fun build(
            media: List<MediaItem>,
            overrides: Map<String, MediaOverride>,
            complete: Boolean,
            today: LocalDate = LocalDate.now(),
            zone: ZoneId = ZoneId.systemDefault(),
            places: Map<String, String> = emptyMap()
        ): Library {
            val merged = media.map { item ->
                val o = overrides[item.id]
                val place = places[item.id]
                if (o == null && place == null) return@map item
                item.copy(
                    isFavorite = o?.isFavorite ?: false,
                    isHidden = o?.isHidden ?: false,
                    album = o?.customAlbum?.takeIf { it.isNotBlank() },
                    place = place
                )
            }
            val visible = merged.filter { !it.isHidden }
            val buckets = visible.filter { it.bucketId.isNotEmpty() }.groupBy { it.bucketId }
                .map { (id, list) -> Album(id, list.first().bucketName.ifBlank { "Sin nombre" }, list) }
            val custom = visible.filter { it.album != null }.groupBy { it.album!! }
                .map { (name, list) -> Album("custom:$name", name, list, isCustom = true) }
            val hidden = merged.filter { it.isHidden }
            val memories = memoriesOf(visible, today, zone)
            val favorites = visible.filter { it.isFavorite }
            val dates = visible.map { Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate() }
            // Import days hold thousands of undated files; they are not "your biggest day".
            val perDay = visible.indices.filter { !visible[it].dateEstimated }.groupingBy { dates[it] }.eachCount()
            return Library(
                timeline = visible,
                favorites = favorites,
                videos = visible.filter { it.isVideo },
                hidden = hidden,
                places = visible.filter { it.place != null }.groupBy { it.place!! }
                    .map { (name, list) -> Album("place:$name", name, list) }
                    .sortedByDescending { it.items.size },
                hiddenAlbums = hidden.groupBy { it.album ?: it.bucketName.ifBlank { "Sin nombre" } }
                    .map { (name, list) -> Album("hidden:$name", name, list) }
                    .sortedByDescending { it.items.first().dateTakenMs },
                stories = storiesOf(memories, favorites, visible, today, zone),
                years = visible.indices.groupBy { dates[it].year }
                    .map { (year, rows) -> YearStat(year, rows.map { visible[it] }) }
                    .sortedByDescending { it.year },
                busiestDay = perDay.maxByOrNull { it.value }?.let { BusiestDay(it.key, it.value) },
                albums = (custom + buckets).sortedWith(
                    compareByDescending<Album> { it.isCustom }.thenByDescending { it.items.first().dateTakenMs }
                ),
                memories = memories,
                complete = complete
            )
        }

        private const val STORY_SLIDES = 12

        /** At most [STORY_SLIDES] photos, spread evenly so one big burst does not fill a whole story. */
        private fun spread(items: List<MediaItem>): List<MediaItem> {
            if (items.size <= STORY_SLIDES) return items
            return List(STORY_SLIDES) { items[it * items.size / STORY_SLIDES] }
        }

        private const val MIN_STORY = 3
        private val MONTHS_AGO = listOf(1 to "Hace un mes", 3 to "Hace 3 meses", 6 to "Hace 6 meses")

        /**
         * The whole set of stories: anniversaries first, then recent look-backs, the biggest day, daily random
         * picks and one per recent month. Only photos with a trustworthy date go into date-based stories.
         */
        fun storiesOf(
            memories: List<Memory>,
            favorites: List<MediaItem>,
            visible: List<MediaItem>,
            today: LocalDate,
            zone: ZoneId = ZoneId.systemDefault()
        ): List<Story> = buildList {
            memories.forEach { m ->
                add(
                    Story(
                        "memory-${m.yearsAgo}",
                        if (m.yearsAgo == 1) "Hace 1 año" else "Hace ${m.yearsAgo} años",
                        "Estos días en ${today.year - m.yearsAgo}",
                        spread(m.items)
                    )
                )
            }
            val photos = visible.filter { !it.isVideo && !it.isScreenshot }
            val dated = photos.filter { !it.dateEstimated }
            val byDay = dated.groupBy { Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate() }

            MONTHS_AGO.forEach { (months, title) ->
                val center = today.minusMonths(months.toLong())
                val around = (-3L..3L).flatMap { byDay[center.plusDays(it)].orEmpty() }
                if (around.size >= MIN_STORY) {
                    add(Story("ago-$months", title, "Estos días de ${MONTHS_ES[center.monthValue - 1]}", spread(around.sortedBy { it.dateTakenMs })))
                }
            }

            byDay.maxByOrNull { it.value.size }?.takeIf { it.value.size >= 10 }?.let { (day, list) ->
                add(
                    Story(
                        "big-day",
                        "Tu gran día",
                        "${list.size} fotos el ${day.dayOfMonth} de ${MONTHS_ES[day.monthValue - 1]}",
                        spread(list.sortedBy { it.dateTakenMs })
                    )
                )
            }

            // Places away from "home" (the place with most photos) make the best stories.
            val byPlace = dated.filter { it.place != null }.groupBy { it.place!! }
            val home = byPlace.maxByOrNull { it.value.size }?.key
            byPlace.filterKeys { it != home }.entries
                .filter { it.value.size >= 5 }
                .sortedByDescending { it.value.size }
                .take(6)
                .forEach { (name, list) ->
                    add(Story("place-$name", name, "${list.size} fotos aquí", spread(list.sortedBy { it.dateTakenMs })))
                }

            val random = java.util.Random(today.toEpochDay())
            if (photos.size >= 6) {
                add(Story("surprise", "Sorpresa", "Elegidas al azar para hoy", photos.shuffled(random).take(STORY_SLIDES).sortedBy { it.dateTakenMs }))
            }
            val undated = photos.filter { it.dateEstimated }
            if (undated.size >= 6) {
                add(Story("archive", "Del archivo", "Fotos sin fecha exacta", undated.shuffled(random).take(STORY_SLIDES)))
            }

            val favoritePhotos = favorites.filter { !it.isVideo }
            if (favoritePhotos.isNotEmpty()) add(Story("favorites", "Favoritas", "Lo que más te gusta", spread(favoritePhotos)))

            // The current month is skipped: it is what the timeline already shows first.
            val byMonth = dated.groupBy {
                val d = Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate()
                d.year * 12 + d.monthValue - 1
            }
            val thisMonth = today.year * 12 + today.monthValue - 1
            byMonth.keys.filter { it < thisMonth }.sortedDescending().take(6).forEach { key ->
                val list = byMonth.getValue(key)
                if (list.size >= MIN_STORY) {
                    val month = MONTHS_ES[key % 12]
                    add(
                        Story(
                            "month-$key",
                            month.replaceFirstChar { it.uppercase() },
                            "Tu $month de ${key / 12}",
                            spread(list.sortedBy { it.dateTakenMs })
                        )
                    )
                }
            }
        }

        /** Photos taken within a week of today's date in earlier years, newest year first. */
        fun memoriesOf(items: List<MediaItem>, today: LocalDate, zone: ZoneId): List<Memory> {
            val byYear = HashMap<Int, MutableList<MediaItem>>()
            for (item in items) {
                if (item.isVideo || item.dateEstimated || item.isScreenshot) continue
                val date = Instant.ofEpochMilli(item.dateTakenMs).atZone(zone).toLocalDate()
                if (date.year >= today.year) continue
                val distance = ChronoUnit.DAYS.between(today.withYear(date.year), date)
                if (distance in -7..7) byYear.getOrPut(today.year - date.year) { ArrayList() }.add(item)
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
                    append(item.place.orEmpty()).append(' ')
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
