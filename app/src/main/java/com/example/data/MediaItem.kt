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
    /** True when neither the metadata nor the file name say when it was taken; the date is only a bound. */
    val dateEstimated: Boolean = false,
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
    val place: String? = null
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaId
        )

    val isScreenshot: Boolean
        get() = bucketName.contains("screenshot", true) || bucketName.contains("captura", true)

    val aspect: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height else 1f

    val durationLabel: String
        get() {
            val total = durationMs / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}
