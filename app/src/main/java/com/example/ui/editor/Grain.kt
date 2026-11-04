package com.example.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Random

/**
 * Fine film grain shared by the preview and the saved copy. A fixed texture keeps both identical and avoids
 * allocating a bitmap per frame; the overlay blend adds light and dark specks without tinting the photo.
 */
internal object Grain {
    private const val SIZE = 512
    private const val SEED = 0x5EEDL

    private val texture: Bitmap by lazy {
        val random = Random(SEED)
        val pixels = IntArray(SIZE * SIZE)
        for (i in pixels.indices) {
            // Centred on mid gray so Overlay leaves the average brightness alone.
            val value = 128 + random.nextInt(97) - 48
            pixels[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        }
    }

    private val image: androidx.compose.ui.graphics.ImageBitmap by lazy { texture.asImageBitmap() }

    internal fun previewShader() = ImageShader(image, TileMode.Repeated, TileMode.Repeated)

    /** The same grain for the bitmap export; [amount] is 0..1. */
    fun draw(canvas: Canvas, width: Int, height: Int, amount: Float) {
        if (amount <= 0f || width <= 0 || height <= 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            alpha = (amount.coerceIn(0f, 1f) * 255).toInt()
            xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.reset()
    }
}

/** Draws the photo and then the grain on top, so crop handles and other overlays stay clean. */
internal fun Modifier.filmGrain(amount: Float): Modifier = drawWithCache {
    if (amount <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
    val shader = Grain.previewShader()
    onDrawWithContent {
        drawContent()
        drawRect(
            brush = ShaderBrush(shader),
            alpha = amount.coerceIn(0f, 1f),
            blendMode = BlendMode.Overlay
        )
    }
}
