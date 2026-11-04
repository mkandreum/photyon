package com.example

import com.example.data.Library
import com.example.data.MediaDates
import com.example.data.MediaItem
import com.example.data.MediaOverride
import com.example.data.MediaSearch
import com.example.data.Story
import com.example.data.dateLabel
import com.example.ui.editor.ColorMath
import com.example.ui.editor.CropRect
import com.example.ui.editor.EditValues
import com.example.ui.editor.PhotoFilter
import com.example.ui.timeline.Density
import com.example.ui.timeline.Mosaic
import com.example.ui.timeline.TimelineRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class MosaicTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun photo(i: Int, date: String = "2024-03-14", w: Int = 4000, h: Int = 3000, name: String = "IMG_$i.jpg", video: Boolean = false) =
        MediaItem(id = i.toString(), mediaId = i.toLong(), isVideo = video, displayName = name, dateTakenMs = at(date), width = w, height = h)

    @Test
    fun `every photo lands in exactly one row in order at every density`() {
        val items = (0 until 500).map { photo(it, date = "2024-03-${(it / 40 + 1).toString().padStart(2, '0')}", w = if (it % 3 == 0) 3000 else 4000) }
            .sortedByDescending { it.dateTakenMs }
        Density.entries.forEach { density ->
            val built = Mosaic.build(items, density, zone)
            val flat = built.rows.filterIsInstance<TimelineRow.Photos>().flatMap { it.items }
            assertEquals(density.name, items.map { it.id }, flat.map { it.id })
            flat.forEach { assertTrue(built.rows[built.rowOfItem.getValue(it.id)] is TimelineRow.Photos) }
        }
    }

    @Test
    fun `row keys are unique so the lazy list never crashes`() {
        val items = (0 until 300).map { photo(it, date = "2023-0${it % 9 + 1}-10") }.sortedByDescending { it.dateTakenMs }
        Density.entries.forEach { d ->
            val keys = Mosaic.build(items, d, zone).rows.map { it.key }
            assertEquals(keys.size, keys.toSet().size)
        }
    }

    @Test
    fun `mosaic rows never have more photos than slots and cells stay inside the row`() {
        val items = (0 until 97).map { photo(it, w = if (it % 2 == 0) 3000 else 4000, h = if (it % 2 == 0) 4000 else 3000) }
        Mosaic.build(items, Density.Moments, zone).rows.filterIsInstance<TimelineRow.Photos>().forEach { row ->
            assertTrue(row.items.size <= row.layout.size)
            row.layout.cells.forEach { c ->
                assertTrue(c.x + c.w <= 1.001f)
                assertTrue(c.y + c.h <= row.layout.heightRatio + 0.001f)
            }
        }
    }

    @Test
    fun `dial anchors are one per month in order`() {
        val items = listOf(photo(1, "2024-03-14"), photo(2, "2024-03-01"), photo(3, "2024-02-20"), photo(4, "2023-12-31"))
        val anchors = Mosaic.build(items, Density.Moments, zone).anchors
        assertEquals(listOf("MAR", "FEB", "DIC"), anchors.map { it.month })
        assertEquals(anchors.map { it.rowIndex }.sorted(), anchors.map { it.rowIndex })
    }

    @Test
    fun `library applies overrides and keeps hidden items out of the timeline`() {
        val items = listOf(photo(1), photo(2), photo(3))
        val lib = Library.build(
            items,
            mapOf("1" to MediaOverride("1", isFavorite = true), "2" to MediaOverride("2", isHidden = true)),
            complete = true, today = LocalDate.parse("2026-01-01"), zone = zone
        )
        assertEquals(listOf("1", "3"), lib.timeline.map { it.id })
        assertEquals(listOf("1"), lib.favorites.map { it.id })
        assertEquals(listOf("2"), lib.hidden.map { it.id })
    }

    @Test
    fun `memories find this week in earlier years only`() {
        val items = listOf(photo(1, "2024-03-14"), photo(2, "2024-03-30"), photo(3, "2026-03-14"), photo(4, "2022-03-12"))
        val memories = Library.memoriesOf(items, LocalDate.parse("2026-03-14"), zone)
        assertEquals(listOf(2, 4), memories.map { it.yearsAgo })
        assertEquals(listOf("1"), memories.first().items.map { it.id })
    }

    @Test
    fun `search matches month year kind and ignores accents and case`() {
        val items = listOf(photo(1, "2023-03-14"), photo(2, "2023-07-01", name = "Cumpleaños.jpg"), photo(3, "2021-03-02", video = true))
        assertEquals(listOf("1"), MediaSearch.filter(items, "Marzo 2023", zone).map { it.id })
        assertEquals(listOf("2"), MediaSearch.filter(items, "CUMPLEANOS", zone).map { it.id })
        assertEquals(listOf("3"), MediaSearch.filter(items, "vídeo", zone).map { it.id })
        assertTrue(MediaSearch.filter(items, "   ", zone).isEmpty())
    }

    @Test
    fun `stories use memories first and cap their slides`() {
        val visible = (0 until 40).map { photo(it, "2024-03-14") }
        val memories = Library.memoriesOf(visible, LocalDate.parse("2026-03-14"), zone)
        val stories = Library.storiesOf(memories, visible.take(3), visible, LocalDate.parse("2026-03-14"), zone)
        assertEquals(listOf("memory-2", "big-day", "surprise", "favorites", "month-${2024 * 12 + 2}"), stories.map { it.id })
        assertEquals(12, stories.first().items.size)
        assertEquals(3, stories.first { it.id == "favorites" }.items.size)
        // The surprise is stable for the whole day.
        val again = Library.storiesOf(memories, emptyList(), visible, LocalDate.parse("2026-03-14"), zone)
        assertEquals(stories[2].items, again[2].items)
    }

    @Test
    fun `story date label shows the tightest range`() {
        fun label(vararg dates: String) = Story("s", "t", "u", dates.mapIndexed { i, d -> photo(i, d) }).dateLabel(zone)
        assertEquals("4 NOV 2024", label("2024-11-04"))
        assertEquals("2-6 NOV 2024", label("2024-11-06", "2024-11-02"))
        assertEquals("MAR-NOV 2024", label("2024-03-01", "2024-11-02"))
        assertEquals("2019-2024", label("2019-03-01", "2024-11-02"))
    }

    @Test
    fun `undated photos stay out of date stories and go to the archive`() {
        val undated = (0 until 20).map { photo(it, "2025-11-04").copy(dateEstimated = true) }
        val recent = (100 until 105).map { photo(it, "2026-10-04") }
        val today = LocalDate.parse("2026-11-04")
        val memories = Library.memoriesOf(undated + recent, today, zone)
        assertTrue(memories.isEmpty())
        val stories = Library.storiesOf(memories, emptyList(), undated + recent, today, zone)
        val ids = stories.map { it.id }
        assertTrue(ids.toString(), "ago-1" in ids && "archive" in ids && "surprise" in ids && "month-${2026 * 12 + 9}" in ids)
        assertEquals("SIN FECHA EXACTA", stories.first { it.id == "archive" }.dateLabel(zone))
        assertEquals(ids.size, ids.toSet().size)
        val lib = Library.build(undated + recent, emptyMap(), true, today, zone)
        assertEquals(LocalDate.parse("2026-10-04"), lib.busiestDay?.date)
    }

    @Test
    fun `places feed search and stories but home is not a story`() {
        val items = (0 until 30).map { photo(it, "2026-06-${(it % 20 + 1).toString().padStart(2, '0')}") }.sortedByDescending { it.dateTakenMs }
        val names = items.associate { it.id to if (it.id.toInt() < 22) "Madrid" else "Cádiz" }
        val lib = Library.build(items, emptyMap(), true, LocalDate.parse("2026-11-04"), zone, names)
        assertEquals(listOf("Madrid", "Cádiz"), lib.places.map { it.name })
        assertTrue("place-Cádiz" in lib.stories.map { it.id })
        assertTrue("place-Madrid" !in lib.stories.map { it.id })
        assertEquals(8, MediaSearch.filter(lib.timeline, "cadiz", zone).size)
    }

    @Test
    fun `screenshots never appear in stories`() {
        val shots = (0 until 30).map { photo(it, "2026-10-04").copy(bucketName = "Screenshots") }
        val today = LocalDate.parse("2026-11-04")
        assertTrue(Library.storiesOf(emptyList(), emptyList(), shots, today, zone).isEmpty())
        assertTrue(Library.memoriesOf(shots, LocalDate.parse("2027-10-04"), zone).isEmpty())
    }

    @Test
    fun `film stocks behave like their reputation`() {
        fun saturationOf(f: PhotoFilter): Float {
            val m = ColorMath.of(EditValues(filter = f))
            val r = m[0] * 200f + m[1] * 100f + m[2] * 100f + m[4]
            val g = m[5] * 200f + m[6] * 100f + m[7] * 100f + m[9]
            val b = m[10] * 200f + m[11] * 100f + m[12] * 100f + m[14]
            return maxOf(r, g, b) - minOf(r, g, b)
        }
        val plain = saturationOf(PhotoFilter.Original)
        assertEquals(0f, saturationOf(PhotoFilter.Acros), 0.5f)
        assertTrue(saturationOf(PhotoFilter.Velvia) > plain * 1.3f)
        assertTrue(saturationOf(PhotoFilter.Ektar) > saturationOf(PhotoFilter.Portra))
        assertTrue(saturationOf(PhotoFilter.ClassicChrome) < plain)
        // Published PixiJS matrix: kept verbatim, offsets scaled to 0..255.
        assertEquals(1.438f, PhotoFilter.Polaroid.matrix()[0], 0.0001f)
        assertEquals(0.24991995f * 255f, PhotoFilter.Kodachrome.matrix()[4], 0.001f)
    }

    @Test
    fun `file names give back lost dates`() {
        val now = at("2026-11-04")
        fun day(name: String) = MediaDates.fromFileName(name, now, zone)?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString()
        }
        assertEquals("2024-03-12", day("IMG-20240312-WA0001.jpg"))
        assertEquals("2021-07-09", day("Screenshot_2021-07-09-18-22-01.png"))
        assertEquals("2023-11-14", day("1700000000000.png"))
        assertEquals(null, day("IMG_1234.JPG"))
        assertEquals(null, day("IMG-20991231-WA0001.jpg"))
        assertEquals(null, day("IMG-20240231-WA0001.jpg"))
    }

    @Test
    fun `color matrices compose like applying them in sequence`() {
        val first = ColorMath.contrastBrightness(1.2f, 0.1f)
        val second = ColorMath.warmth(0.1f)
        val rgb = floatArrayOf(100f, 150f, 200f)
        fun apply(m: FloatArray, c: FloatArray) = FloatArray(3) { r ->
            m[r * 5] * c[0] + m[r * 5 + 1] * c[1] + m[r * 5 + 2] * c[2] + m[r * 5 + 3] * 255f + m[r * 5 + 4]
        }
        val stepwise = apply(second, apply(first, rgb))
        val combined = apply(ColorMath.then(first, second), rgb)
        for (i in 0..2) assertEquals(stepwise[i], combined[i], 0.01f)
        assertTrue(EditValues().isOriginal)
        ColorMath.of(EditValues()).zip(ColorMath.identity().toList()).forEach { (a, b) -> assertEquals(b, a, 0.0001f) }
    }

    @Test
    fun `crop stays inside the image and keeps a locked ratio`() {
        assertEquals(CropRect(), CropRect.centered(null, 1.5f))
        val square = CropRect.centered(1f, 2f)
        assertEquals(0.5f, square.width, 0.001f)
        assertEquals(1f, square.height, 0.001f)
        val moved = CropRect(0.1f, 0.1f, 0.5f, 0.5f).moved(-1f, 2f)
        assertEquals(0f, moved.left, 0.001f)
        assertEquals(1f, moved.bottom, 0.001f)
        val resized = CropRect.resized(square, 1, 1, 0.9f, 0.9f, 1f, 2f)
        assertEquals(2f, (resized.width * 2f) / resized.height, 1.01f)
        assertTrue(resized.right <= 1f && resized.bottom <= 1.001f)
        val free = CropRect.resized(CropRect(), 0, 0, 0.99f, 0.99f, null, 1f)
        assertTrue(free.width >= CropRect.MIN - 0.001f && free.height >= CropRect.MIN - 0.001f)
    }
}
