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

/** The numbers behind each roll. Polaroid and Kodachrome are published matrices; the rest are looks built from how the stock behaves. */
private object Stocks {
    private fun chain(vararg m: FloatArray) = m.reduce(ColorMath::then)

    /** PixiJS's ColorMatrixFilter presets, whose offsets are in 0..1. */
    private fun pixi(vararg v: Float) = floatArrayOf(
        v[0], v[1], v[2], v[3], v[4] * 255f,
        v[5], v[6], v[7], v[8], v[9] * 255f,
        v[10], v[11], v[12], v[13], v[14] * 255f,
        0f, 0f, 0f, 1f, 0f
    )

    val polaroid = pixi(
        1.438f, -0.062f, -0.062f, 0f, 0f,
        -0.122f, 1.378f, -0.122f, 0f, 0f,
        -0.016f, -0.016f, 1.483f, 0f, 0f
    )
    val kodachrome = pixi(
        1.1285582f, -0.39673823f, -0.039925592f, 0f, 0.24991995f,
        -0.1640434f, 1.0835252f, -0.054988053f, 0f, 0.09698983f,
        -0.16786011f, -0.56034166f, 1.6014851f, 0f, 0.13972482f
    )

    /** Warm yellows and reds, moderate contrast. */
    val gold = chain(ColorMath.warmth(0.1f), ColorMath.saturation(1.1f), ColorMath.contrastBrightness(1.03f, 0.01f))

    /** Moderate saturation and contrast, kind to skin. */
    val portra = chain(ColorMath.saturation(0.9f), ColorMath.warmth(0.05f), ColorMath.contrastBrightness(0.94f, 0.02f))

    /** The most saturated and contrasty Kodak negative. */
    val ektar = chain(ColorMath.saturation(1.32f), ColorMath.contrastBrightness(1.12f, 0f), ColorMath.warmth(0.02f))

    /** Fuji's neutral baseline: true colors, moderate contrast. */
    val provia = chain(ColorMath.saturation(1.04f), ColorMath.contrastBrightness(1.03f, 0f))

    /** Vivid and contrasty, with blues drifting towards magenta. */
    val velvia = chain(
        ColorMath.saturation(1.42f),
        floatArrayOf(
            1f, 0f, 0.04f, 0f, 0f,
            0f, 1f, -0.02f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ),
        ColorMath.contrastBrightness(1.16f, -0.01f)
    )

    /** Documentary look: muted, less magenta, slightly cool shadows. */
    val classicChrome = chain(
        ColorMath.saturation(0.74f),
        floatArrayOf(
            0.97f, 0.02f, 0f, 0f, 0f,
            0f, 1.02f, 0f, 0f, 0f,
            0f, 0.02f, 0.98f, 0f, 5f,
            0f, 0f, 0f, 1f, 0f
        ),
        ColorMath.contrastBrightness(1.06f, 0f)
    )

    /** Fine-grain black and white weighted towards green, as panchromatic film sees. */
    val acros = chain(
        floatArrayOf(
            0.3f, 0.6f, 0.1f, 0f, 0f,
            0.3f, 0.6f, 0.1f, 0f, 0f,
            0.3f, 0.6f, 0.1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ),
        ColorMath.contrastBrightness(1.14f, 0f)
    )
}

/** Each filter is a roll of film: brand, stock, ISO and the colors of its box. */
enum class PhotoFilter(
    val label: String,
    val brand: String,
    val iso: String,
    val casing: Long,
    val band: Long,
    val onCasing: Long,
    /** Film grain strength, 0..1, applied by the editor preview and export. */
    val grain: Float = 0f,
    val matrix: () -> FloatArray
) {
    Original("Original", "SIN", "FILTRO", 0xFF2E2A27, 0xFF9E958E, 0xFFF6F1EC, grain = 0f, matrix = { ColorMath.identity() }),
    Polaroid("Polaroid", "POLAROID", "600", 0xFFF2EEE6, 0xFFE4483B, 0xFF1F1C1A, grain = 0.20f, matrix = { Stocks.polaroid }),
    Kodachrome("Kodachrome", "KODAK", "64", 0xFFFFC21C, 0xFFD62B1F, 0xFF1F1C1A, grain = 0.14f, matrix = { Stocks.kodachrome }),
    Gold("Gold", "KODAK", "200", 0xFFFFB400, 0xFFC8102E, 0xFF1F1C1A, grain = 0.10f, matrix = { Stocks.gold }),
    Portra("Portra", "KODAK", "400", 0xFFF4E9D3, 0xFFE0A526, 0xFF1F1C1A, grain = 0.07f, matrix = { Stocks.portra }),
    Ektar("Ektar", "KODAK", "100", 0xFFD9262E, 0xFFFFC21C, 0xFFFFFFFF, grain = 0.06f, matrix = { Stocks.ektar }),
    Velvia("Velvia", "FUJI", "50", 0xFF0B7A4B, 0xFFE6007E, 0xFFFFFFFF, grain = 0.05f, matrix = { Stocks.velvia }),
    Provia("Provia", "FUJI", "100", 0xFF1B9E5A, 0xFF0B3D91, 0xFFFFFFFF, grain = 0.05f, matrix = { Stocks.provia }),
    ClassicChrome("Chrome", "FUJI", "CLASSIC", 0xFF4F5B52, 0xFFC9B27A, 0xFFFFFFFF, grain = 0.08f, matrix = { Stocks.classicChrome }),
    Acros("Acros", "FUJI", "100", 0xFF17191A, 0xFFD8D8D8, 0xFFFFFFFF, grain = 0.18f, matrix = { Stocks.acros }),
    Safelight("Cuarto oscuro", "PHOTYON", "ROJO", 0xFF0C0B0A, 0xFFFF5B3A, 0xFFFF5B3A, grain = 0.12f, matrix = { ColorMath.safelight() })
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
