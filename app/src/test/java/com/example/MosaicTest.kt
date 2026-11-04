package com.example

import com.example.data.Library
import com.example.data.MediaItem
import com.example.data.MediaOverride
import com.example.data.MediaSearch
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
}
