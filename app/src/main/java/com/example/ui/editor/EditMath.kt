package com.example.ui.editor

import androidx.compose.runtime.Immutable

/** 4x5 color matrices (Android layout, offsets in 0..255). Pure, so preview and export always agree. */
object ColorMath {
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** The matrix that applies [second] after [first]. */
    fun then(first: FloatArray, second: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (r in 0..3) {
            val row = r * 5
            for (c in 0..3) {
                out[row + c] = second[row] * first[c] + second[row + 1] * first[5 + c] +
                    second[row + 2] * first[10 + c] + second[row + 3] * first[15 + c]
            }
            out[row + 4] = second[row] * first[4] + second[row + 1] * first[9] +
                second[row + 2] * first[14] + second[row + 3] * first[19] + second[row + 4]
        }
        return out
    }

    fun saturation(s: Float): FloatArray {
        val i = 1f - s
        return floatArrayOf(
            LR * i + s, LG * i, LB * i, 0f, 0f,
            LR * i, LG * i + s, LB * i, 0f, 0f,
            LR * i, LG * i, LB * i + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /** Contrast pivots on mid gray; [brightness] is -1..1. */
    fun contrastBrightness(contrast: Float, brightness: Float): FloatArray {
        val o = (1f - contrast) * 127.5f + brightness * 255f
        return floatArrayOf(
            contrast, 0f, 0f, 0f, o,
            0f, contrast, 0f, 0f, o,
            0f, 0f, contrast, 0f, o,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /** Positive warms (more red, less blue); negative cools. */
    fun warmth(w: Float) = floatArrayOf(
        1f + w, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f - w, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    /** Black and white printed under a darkroom safelight. */
    fun safelight() = floatArrayOf(
        LR, LG, LB, 0f, 12f,
        LR * 0.38f, LG * 0.38f, LB * 0.38f, 0f, 0f,
        LR * 0.22f, LG * 0.22f, LB * 0.22f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    )

    fun of(values: EditValues): FloatArray {
        var m = values.filter.matrix()
        m = then(m, saturation(values.saturation))
        m = then(m, warmth(values.warmth))
        return then(m, contrastBrightness(values.contrast, values.brightness))
    }
}

enum class PhotoFilter(val label: String, val matrix: () -> FloatArray) {
    Original("Original", { ColorMath.identity() }),
    Vivid("Vivo", { ColorMath.then(ColorMath.saturation(1.35f), ColorMath.contrastBrightness(1.08f, 0f)) }),
    Warm("Cálido", { ColorMath.then(ColorMath.warmth(0.12f), ColorMath.saturation(1.1f)) }),
    Cold("Frío", { ColorMath.warmth(-0.12f) }),
    Analog("Analógico", {
        ColorMath.then(
            ColorMath.then(ColorMath.saturation(0.8f), ColorMath.warmth(0.06f)),
            ColorMath.contrastBrightness(0.85f, 0.03f)
        )
    }),
    Silver("Plata", { ColorMath.then(ColorMath.saturation(0f), ColorMath.contrastBrightness(0.95f, 0.03f)) }),
    Ink("Tinta", { ColorMath.then(ColorMath.saturation(0f), ColorMath.contrastBrightness(1.25f, 0f)) }),
    Safelight("Cuarto oscuro", { ColorMath.safelight() })
}

@Immutable
data class EditValues(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
    val filter: PhotoFilter = PhotoFilter.Original
) {
    val isOriginal: Boolean get() = this == EditValues()
}

/** Crop in fractions of the image, always kept inside it and above a minimum size. */
@Immutable
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun moved(dx: Float, dy: Float): CropRect {
        val x = dx.coerceIn(-left, 1f - right)
        val y = dy.coerceIn(-top, 1f - bottom)
        return CropRect(left + x, top + y, right + x, bottom + y)
    }

    companion object {
        const val MIN = 0.1f

        /** Largest centered rect of [ratio] (width/height) in an image of [imageAspect]; null ratio is the whole image. */
        fun centered(ratio: Float?, imageAspect: Float): CropRect {
            if (ratio == null) return CropRect()
            val relative = ratio / imageAspect
            return if (relative >= 1f) {
                val h = 1f / relative
                CropRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
            } else {
                CropRect((1f - relative) / 2f, 0f, (1f + relative) / 2f, 1f)
            }
        }

        /**
         * Drags one corner ([cornerX]/[cornerY]: 0 near, 1 far edge) to a new position. With a [ratio] the rect
         * keeps that proportion, driven by the horizontal movement.
         */
        fun resized(
            rect: CropRect,
            cornerX: Int,
            cornerY: Int,
            x: Float,
            y: Float,
            ratio: Float?,
            imageAspect: Float
        ): CropRect {
            var l = rect.left
            var t = rect.top
            var r = rect.right
            var b = rect.bottom
            if (cornerX == 0) l = x.coerceIn(0f, r - MIN) else r = x.coerceIn(l + MIN, 1f)
            if (ratio == null) {
                if (cornerY == 0) t = y.coerceIn(0f, b - MIN) else b = y.coerceIn(t + MIN, 1f)
                return CropRect(l, t, r, b)
            }
            val relative = ratio / imageAspect
            var w = r - l
            var h = w / relative
            val room = if (cornerY == 0) rect.bottom else 1f - rect.top
            if (h > room) {
                h = room
                w = h * relative
                if (cornerX == 0) l = r - w else r = l + w
            }
            if (cornerY == 0) t = rect.bottom - h else b = rect.top + h
            return CropRect(l, t, r, b)
        }
    }
}
