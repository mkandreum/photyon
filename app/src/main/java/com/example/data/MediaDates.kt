package com.example.data

import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** Recovers a capture date from file names, for media that lost its metadata (WhatsApp, restores, downloads). */
object MediaDates {
    private val DATE = Regex("""(?<!\d)(20[0-3]\d|19[89]\d)[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?!\d)""")
    private val EPOCH = Regex("""(?<!\d)(1[2-9]\d{8})(\d{3})?(?!\d)""")

    /** Noon of the date found in [name] ("IMG-20240312-WA0001", "Screenshot_2024-03-12"), or an epoch stamp; null if none. */
    fun fromFileName(name: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        DATE.find(name)?.let { m ->
            val (y, mo, d) = m.destructured
            val ms = try {
                LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            } catch (e: DateTimeException) {
                return@let
            }
            if (ms <= nowMs) return ms
        }
        EPOCH.find(name)?.let { m ->
            val seconds = m.groupValues[1].toLong()
            val ms = seconds * 1000 + (m.groupValues[2].toLongOrNull() ?: 0L)
            if (ms <= nowMs) return ms
        }
        return null
    }
}
