package com.example.ui.timeline

import androidx.compose.runtime.Immutable
import com.example.data.MONTHS_ES
import com.example.data.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** A photo's slot in a row. All values are fractions of the row's width. */
@Immutable
data class Cell(val x: Float, val y: Float, val w: Float, val h: Float)

/** Pre-sized row shape: heights are known before any image loads, so the list never jumps. */
@Immutable
class RowLayout(val name: String, val heightRatio: Float, val cells: List<Cell>) {
    val size: Int get() = cells.size

    companion object {
        private const val T = 1f / 3f
        val Wide = RowLayout("wide", 0.62f, listOf(Cell(0f, 0f, 1f, 0.62f)))
        val Square = RowLayout("square", 0.92f, listOf(Cell(0f, 0f, 1f, 0.92f)))
        val Pair = RowLayout("pair", 0.66f, listOf(Cell(0f, 0f, 0.5f, 0.66f), Cell(0.5f, 0f, 0.5f, 0.66f)))
        val Trio = RowLayout("trio", T, List(3) { Cell(it * T, 0f, T, T) })
        val LeadLeft = RowLayout(
            "leadL", 2 * T,
            listOf(Cell(0f, 0f, 2 * T, 2 * T), Cell(2 * T, 0f, T, T), Cell(2 * T, T, T, T))
        )
        val LeadRight = RowLayout(
            "leadR", 2 * T,
            listOf(Cell(T, 0f, 2 * T, 2 * T), Cell(0f, 0f, T, T), Cell(0f, T, T, T))
        )

        private val uniforms = HashMap<Int, RowLayout>()

        fun uniform(columns: Int): RowLayout = synchronized(uniforms) {
            uniforms.getOrPut(columns) {
                val w = 1f / columns
                RowLayout("u$columns", w, List(columns) { Cell(it * w, 0f, w, w) })
            }
        }
    }
}

enum class Density(val label: String) {
    Moments("Momentos"), Months("Meses"), Years("Años");

    val tighter: Density get() = entries.getOrElse(ordinal + 1) { this }
    val looser: Density get() = entries.getOrElse(ordinal - 1) { this }
}

sealed interface TimelineRow {
    val key: String

    @Immutable
    data class Header(
        override val key: String,
        val numeral: String,
        val title: String,
        val subtitle: String,
        val big: Boolean
    ) : TimelineRow

    @Immutable
    class Photos(override val key: String, val layout: RowLayout, val items: List<MediaItem>) : TimelineRow
}

/** One scrubbable stop of the time dial. */
@Immutable
data class TimeAnchor(val rowIndex: Int, val month: String, val year: Int)

@Immutable
class TimelineRows(
    val rows: List<TimelineRow>,
    val items: List<MediaItem>,
    val rowOfItem: Map<String, Int>,
    val positionOfItem: Map<String, Int>,
    val anchors: List<TimeAnchor>
) {
    companion object {
        val EMPTY = TimelineRows(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyList())
    }
}

object Mosaic {
    private val SPANISH = Locale.forLanguageTag("es-ES")

    private val MediaItem.isLandscape get() = aspect > 1.15f

    /** Picks the next shape from the photos' orientation; seeded by id so a day's layout never reshuffles. */
    fun nextLayout(items: List<MediaItem>, from: Int, previous: RowLayout?): RowLayout {
        val remaining = items.size - from
        val first = items[from]
        val seed = (first.id.hashCode() and 0x7fffffff) % 100
        val options: List<RowLayout> = when {
            remaining == 1 -> listOf(if (first.isLandscape) RowLayout.Wide else RowLayout.Square)
            remaining == 2 -> listOf(RowLayout.Pair)
            first.isLandscape && seed < 45 -> listOf(RowLayout.Wide, RowLayout.LeadLeft)
            first.isLandscape -> listOf(RowLayout.LeadLeft, RowLayout.Trio, RowLayout.LeadRight)
            seed < 34 -> listOf(RowLayout.Pair, RowLayout.Trio)
            seed < 67 -> listOf(RowLayout.LeadRight, RowLayout.Trio)
            else -> listOf(RowLayout.LeadLeft, RowLayout.Pair)
        }
        return options.firstOrNull { it !== previous && it.size <= remaining } ?: options.first { it.size <= remaining }
    }

    fun build(items: List<MediaItem>, density: Density, zone: ZoneId = ZoneId.systemDefault()): TimelineRows {
        val rows = ArrayList<TimelineRow>(items.size / 2 + 16)
        val rowOfItem = HashMap<String, Int>(items.size * 2)
        val positionOfItem = HashMap<String, Int>(items.size * 2)
        val anchors = ArrayList<TimeAnchor>()
        items.forEachIndexed { i, item -> positionOfItem[item.id] = i }

        val dated = items.map { it to Instant.ofEpochMilli(it.dateTakenMs).atZone(zone).toLocalDate() }
        val groups: Collection<List<Pair<MediaItem, LocalDate>>> = when (density) {
            Density.Moments -> dated.groupConsecutive { it.second.toEpochDay() }
            Density.Months -> dated.groupConsecutive { it.second.year * 12L + it.second.monthValue }
            Density.Years -> dated.groupConsecutive { it.second.year.toLong() }
        }

        var lastMonth = -1L
        for (group in groups) {
            val date = group.first().second
            val monthKey = date.year * 12L + date.monthValue
            if (monthKey != lastMonth) {
                anchors.add(TimeAnchor(rows.size, MONTHS_ES[date.monthValue - 1].take(3).uppercase(), date.year))
                lastMonth = monthKey
            }
            rows.add(
                headerFor(
                    density, date, group.size,
                    group.count { it.first.dateEstimated } * 2 > group.size,
                    group.firstNotNullOfOrNull { it.first.place }
                )
            )
            val photos = group.map { it.first }
            var at = 0
            var previous: RowLayout? = null
            while (at < photos.size) {
                val layout = when (density) {
                    Density.Moments -> nextLayout(photos, at, previous)
                    Density.Months -> RowLayout.uniform(4)
                    Density.Years -> RowLayout.uniform(8)
                }
                val slice = photos.subList(at, minOf(at + layout.size, photos.size))
                slice.forEach { rowOfItem[it.id] = rows.size }
                rows.add(TimelineRow.Photos("r:${density.name}:${slice.first().id}", layout, slice))
                previous = layout
                at += slice.size
            }
        }
        return TimelineRows(rows, items, rowOfItem, positionOfItem, anchors)
    }

    private fun headerFor(density: Density, date: LocalDate, count: Int, estimated: Boolean, place: String?): TimelineRow.Header {
        val noun = if (count == 1) "elemento" else "elementos"
        return when (density) {
            Density.Moments -> TimelineRow.Header(
                key = "h:${date.toEpochDay()}",
                numeral = date.dayOfMonth.toString(),
                title = "${MONTHS_ES[date.monthValue - 1]} ${date.year}",
                subtitle = (
                    if (estimated) "fecha aproximada · $count"
                    else listOfNotNull(date.dayOfWeek.getDisplayName(TextStyle.FULL, SPANISH), "$count", place).joinToString(" · ")
                    ).uppercase(),
                big = true
            )
            Density.Months -> TimelineRow.Header(
                key = "m:${date.year}-${date.monthValue}",
                numeral = "",
                title = "${MONTHS_ES[date.monthValue - 1].replaceFirstChar { it.uppercase() }} ${date.year}",
                subtitle = "$count ${noun.uppercase()}",
                big = false
            )
            Density.Years -> TimelineRow.Header(
                key = "y:${date.year}",
                numeral = date.year.toString(),
                title = "",
                subtitle = "$count ${noun.uppercase()}",
                big = true
            )
        }
    }

    /** Text of the floating pill that says where in time the list is. */
    fun pillLabel(item: MediaItem, zone: ZoneId = ZoneId.systemDefault()): String {
        if (item.dateEstimated) return "SIN FECHA EXACTA"
        val d = Instant.ofEpochMilli(item.dateTakenMs).atZone(zone).toLocalDate()
        val date = "${d.dayOfMonth} ${MONTHS_ES[d.monthValue - 1].take(3).uppercase()} ${d.year}"
        return if (item.place != null) "$date · ${item.place.uppercase()}" else date
    }

    /** The catalog is already sorted by date, so consecutive grouping is linear and keeps the order. */
    private inline fun <T> List<T>.groupConsecutive(key: (T) -> Long): List<List<T>> {
        val out = ArrayList<List<T>>()
        var start = 0
        while (start < size) {
            val k = key(this[start])
            var end = start + 1
            while (end < size && key(this[end]) == k) end++
            out.add(subList(start, end))
            start = end
        }
        return out
    }
}
