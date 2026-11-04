package com.example.ui.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.data.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Edits are always written as a new file in Photyon's folder; the original is never touched. */
object EditorStore {
    private const val FOLDER = "Photyon"
    private const val MAX_PHOTO_SIDE = 8192

    fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            // Sampling skips most of the decode work, unlike an exact target size.
            val sample = Integer.highestOneBit((maxOf(w, h) / maxSide).coerceAtLeast(1))
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }

    fun transformed(bitmap: Bitmap, quarterTurns: Int, flipped: Boolean): Bitmap {
        if (quarterTurns % 4 == 0 && !flipped) return bitmap
        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            postRotate(quarterTurns * 90f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun insert(
        ctx: Context,
        item: MediaItem,
        video: Boolean,
        write: (OutputStream) -> Unit
    ): Uri {
        val base = item.displayName.substringBeforeLast('.')
        val stamp = System.currentTimeMillis() / 1000
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_ed$stamp.${if (video) "mp4" else "jpg"}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/$FOLDER"
            )
            // Stays next to the original in the timeline.
            put(MediaStore.MediaColumns.DATE_TAKEN, item.dateTakenMs)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: error("No se pudo crear el archivo")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("No se pudo escribir el archivo")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    suspend fun savePhoto(
        ctx: Context,
        item: MediaItem,
        quarterTurns: Int,
        flipped: Boolean,
        crop: CropRect,
        values: EditValues
    ): Uri = withContext(Dispatchers.Default) {
        val original = decode(ctx, item.uri, MAX_PHOTO_SIDE)
        val turned = transformed(original, quarterTurns, flipped)
        val x = (crop.left * turned.width).toInt().coerceIn(0, turned.width - 1)
        val y = (crop.top * turned.height).toInt().coerceIn(0, turned.height - 1)
        val w = (crop.width * turned.width).toInt().coerceIn(1, turned.width - x)
        val h = (crop.height * turned.height).toInt().coerceIn(1, turned.height - y)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (!values.isOriginal) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(ColorMath.of(values)))
        Canvas(out).drawBitmap(turned, -x.toFloat(), -y.toFloat(), paint)
        if (turned !== original) turned.recycle()
        original.recycle()
        try {
            withContext(Dispatchers.IO) {
                insert(ctx, item, video = false) { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
        } finally {
            out.recycle()
        }
    }

    data class VideoEdit(
        val startMs: Long,
        val endMs: Long,
        val muted: Boolean,
        val speed: Float,
        val quarterTurns: Int,
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val filter: PhotoFilter = PhotoFilter.Original
    )

    /** Color effects shared by the live preview and the export, so what is seen is what is saved. */
    @OptIn(UnstableApi::class)
    fun colorEffects(brightness: Float, contrast: Float, saturation: Float, filter: PhotoFilter = PhotoFilter.Original): List<Effect> = buildList {
        if (filter != PhotoFilter.Original) {
            val m = filter.matrix()
            // Media3's matrix has no offsets (and reads column by column), so they become a brightness shift.
            val rgb = floatArrayOf(
                m[0], m[5], m[10], 0f,
                m[1], m[6], m[11], 0f,
                m[2], m[7], m[12], 0f,
                0f, 0f, 0f, 1f
            )
            add(RgbMatrix { _, _ -> rgb })
            val lift = (m[4] + m[9] + m[14]) / 3f / 255f
            if (lift != 0f) add(Brightness(lift.coerceIn(-1f, 1f)))
        }
        if (brightness != 0f) add(Brightness(brightness.coerceIn(-1f, 1f)))
        if (contrast != 1f) add(Contrast((contrast - 1f).coerceIn(-1f, 1f)))
        if (saturation != 1f) add(HslAdjustment.Builder().adjustSaturation((saturation - 1f) * 100f).build())
    }

    /**
     * Must run on the main thread: Transformer is bound to the looper it is built on. Keeps the source's quality when
     * the device can; 4K HDR encoders often refuse to start, so a failure retries once at 1080p SDR.
     */
    @OptIn(UnstableApi::class)
    suspend fun exportVideo(ctx: Context, item: MediaItem, edit: VideoEdit, onProgress: (Float) -> Unit): Uri {
        val temp = File(ctx.cacheDir, "edit_${System.nanoTime()}.mp4")
        try {
            try {
                transform(ctx, item, edit, temp, downscale = false, onProgress)
            } catch (e: ExportException) {
                android.util.Log.w("VideoEditor", "export at full quality failed, retrying at 1080p", e)
                temp.delete()
                onProgress(0f)
                transform(ctx, item, edit, temp, downscale = true, onProgress)
            }
            return withContext(Dispatchers.IO) {
                insert(ctx, item, video = true) { out -> temp.inputStream().use { it.copyTo(out) } }
            }
        } finally {
            temp.delete()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun transform(
        ctx: Context,
        item: MediaItem,
        edit: VideoEdit,
        out: File,
        downscale: Boolean,
        onProgress: (Float) -> Unit
    ) {
        val clip = ExoMediaItem.Builder()
            .setUri(item.uri)
            .setClippingConfiguration(
                ExoMediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(edit.startMs)
                    .setEndPositionMs(edit.endMs)
                    .build()
            )
            .build()
        val video = buildList<Effect> {
            addAll(colorEffects(edit.brightness, edit.contrast, edit.saturation, edit.filter))
            if (edit.quarterTurns % 4 != 0) {
                // Media3 rotates counter-clockwise; the editor turns clockwise.
                add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-90f * edit.quarterTurns).build())
            }
            if (edit.speed != 1f) add(SpeedChangeEffect(edit.speed))
            if (downscale) {
                val landscape = (item.aspect > 1f) != (edit.quarterTurns % 2 != 0)
                add(
                    Presentation.createForWidthAndHeight(
                        if (landscape) 1920 else 1080,
                        if (landscape) 1080 else 1920,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            }
        }
        val audio = if (edit.speed != 1f && !edit.muted) {
            listOf(SonicAudioProcessor().apply { setSpeed(edit.speed) })
        } else emptyList()
        val edited = EditedMediaItem.Builder(clip)
            .setRemoveAudio(edit.muted)
            .setEffects(Effects(audio, video))
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(listOf(edited)).build())
            .apply { if (downscale) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .build()

        coroutineScope {
            val transformer = CompletableDeferred<Transformer>()
            val poll = launch {
                val running = transformer.await()
                val holder = ProgressHolder()
                while (isActive) {
                    if (running.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(200)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    val built = Transformer.Builder(ctx)
                        .apply { if (downscale) setVideoMimeType(MimeTypes.VIDEO_H264) }
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    built.start(composition, out.path)
                    transformer.complete(built)
                    cont.invokeOnCancellation { built.cancel() }
                }
            } finally {
                poll.cancel()
            }
        }
    }
}
