package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File

/** Persistent on-disk thumbnails (JPEG) so the grid never decodes full-size media while scrolling. */
object ThumbnailStore {
    private const val DIR = "thumbs"
    private const val PREFS = "thumb_prefs"
    private const val KEY_INITIAL_DONE = "initial_preload_done"
    private val THUMB_SIZE = Size(400, 400)
    private val gate = Semaphore(4)

    data class Progress(val done: Int, val total: Int, val firstRun: Boolean)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    private fun fileFor(ctx: Context, id: String) =
        File(dir(ctx), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    fun fileIfExists(ctx: Context, id: String): File? = fileFor(ctx, id).takeIf { it.exists() && it.length() > 0 }

    suspend fun ensure(ctx: Context, item: MediaItem): File? = withContext(Dispatchers.IO) {
        val out = fileFor(ctx, item.id)
        if (out.exists() && out.length() > 0) return@withContext out
        gate.withPermit {
            try {
                val bmp: Bitmap = ctx.contentResolver.loadThumbnail(Uri.parse(item.uri), THUMB_SIZE, null)
                val tmp = File(out.parentFile, out.name + ".tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                if (tmp.renameTo(out)) out else null
            } catch (e: Exception) {
                android.util.Log.w("ThumbnailStore", "thumb failed for ${item.uri}: ${e.message}")
                null
            }
        }
    }

    /** Generates every missing thumbnail. Reports progress; first install is flagged so the UI can show it. */
    suspend fun preload(ctx: Context, items: List<MediaItem>) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val firstRun = !prefs.getBoolean(KEY_INITIAL_DONE, false)
        val missing = withContext(Dispatchers.IO) { items.filter { fileIfExists(ctx, it.id) == null } }
        if (missing.isEmpty()) {
            if (items.isNotEmpty()) prefs.edit().putBoolean(KEY_INITIAL_DONE, true).apply()
            _progress.value = null
            return
        }
        var done = 0
        _progress.value = Progress(0, missing.size, firstRun)
        try {
            coroutineScope {
                missing.chunked(64).forEach { chunk ->
                    chunk.map { async { ensure(ctx, it) } }.awaitAll()
                    done += chunk.size
                    _progress.value = Progress(done, missing.size, firstRun)
                }
            }
            prefs.edit().putBoolean(KEY_INITIAL_DONE, true).apply()
        } finally {
            _progress.value = null
        }
    }
}
