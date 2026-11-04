package com.example.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable

/** One photo or video. Image and video MediaStore ids overlap, so ids are "123" / "v123". */
@Immutable
data class MediaItem(
    val id: String,
    val mediaId: Long,
    val isVideo: Boolean,
    val displayName: String,
    val dateTakenMs: Long,
    val dateModifiedSec: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val mimeType: String = "",
    val bucketId: String = "",
    val bucketName: String = "",
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val album: String? = null,
    val rotation: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaId
        )

    /** Width / height after applying the user's rotation; 1 when unknown. */
    val aspect: Float
        get() {
            if (width <= 0 || height <= 0) return 1f
            val quarterTurned = ((rotation / 90f).toInt() % 2) != 0
            return if (quarterTurned) height.toFloat() / width else width.toFloat() / height
        }

    val hasColorEdits: Boolean get() = brightness != 0f || contrast != 1f || saturation != 1f

    val durationLabel: String
        get() {
            val total = durationMs / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}
