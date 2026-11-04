package com.example

import com.example.data.Library
import com.example.data.MediaItem
import com.example.data.MediaOverride
import com.example.data.MediaSearch
import com.example.data.Story
import com.example.data.dateLabel
import com.example.ui.editor.ColorMath
import com.example.ui.editor.CropRect
import com.example.ui.editor.EditValues
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
        val items = listOf(photo(1, "2024-03-14"), photo(2, "2024-03-20"), photo(3, "2026-03-14"), photo(4, "2022-03-12"))
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
        val stories = Library.storiesOf(memories, visible.take(3), visible, LocalDate.parse("2026-03-14"))
        assertEquals(listOf("memory-2", "surprise", "favorites"), stories.map { it.id })
        assertEquals(12, stories.first().items.size)
        assertEquals(3, stories.last().items.size)
        // The surprise is stable for the whole day.
        val again = Library.storiesOf(memories, emptyList(), visible, LocalDate.parse("2026-03-14"))
        assertEquals(stories[1].items, again[1].items)
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
