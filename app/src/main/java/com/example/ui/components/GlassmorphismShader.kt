package com.example.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.example.ui.theme.GlassBarBorder
import com.example.ui.theme.GlassSpecular
import org.intellij.lang.annotations.Language

@Language("AGSL")
val LIQUID_GLASS_SHADER = """
    uniform shader image;
    uniform float4 barRect;          // [left, top, right, bottom]
    uniform float4 densityBarRect;   // [left, top, right, bottom]
    uniform float4 searchBarRect;    // [left, top, right, bottom]
    uniform float4 searchFabRect;    // [left, top, right, bottom]
    uniform float4 selectBtnRect;     // [left, top, right, bottom]
    uniform float4 collapsedFabRect;  // [left, top, right, bottom]
    uniform float4 pageIndicatorRect; // [left, top, right, bottom]

    uniform float4 bounds;           // union of all rects, expanded; early-out outside

    bool inRect(float2 c, float4 r) {
        return c.x >= r.x - 2.0 && c.x <= r.z + 2.0 && c.y >= r.y - 2.0 && c.y <= r.w + 2.0 &&
               r.z > r.x && r.w > r.y;
    }

    half4 applyGlassEffect(float2 coord, float4 rect, half4 baseColor) {
        float2 size = rect.zw - rect.xy;
        float2 center = (rect.xy + rect.zw) * 0.5;
        float r = size.y * 0.5;
        float2 halfSize = size * 0.5;
        float2 local = coord - center;

        // Rounded pill SDF
        float2 q = abs(local) - (halfSize - r);
        float sdf = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
        if (sdf > 1.5) return baseColor;
        float edgeAlpha = 1.0 - smoothstep(0.0, 1.5, sdf);

        float w = min(r * 0.85, 20.0);
        float u = clamp(-sdf / w, 0.0, 1.0);
        float inv = 1.0 - u;
        float normalTilt = inv * sqrt(inv);

        // Outward direction from the nearest cap centre
        float capX = max(0.0, halfSize.x - r);
        float2 dir;
        if (abs(local.x) > capX) {
            float2 diff = float2(local.x - sign(local.x) * capX, local.y);
            float len = length(diff);
            dir = len > 0.001 ? diff / len : float2(sign(local.x), 0.0);
        } else {
            dir = float2(0.0, local.y >= 0.0 ? 1.0 : -1.0);
        }

        float3 N = normalize(float3(dir * normalTilt * 2.5, 1.0));

        // Cheap refraction (eta = 0.55)
        float k = 1.0 - 0.3025 * (1.0 - N.z * N.z);
        float refractScale = sqrt(k) - 0.55 * N.z;
        float2 distortion = -dir * (refractScale * normalTilt * (r * 0.8) * clamp(1.0 - sdf, 0.0, 1.0));
        float2 dc = coord + distortion;

        // 5 taps total: 3 for green (centre + 2 diagonal blur), 1 red, 1 blue
        half4 g0 = image.eval(dc);
        half4 g1 = image.eval(dc + float2(-1.5, -1.5));
        half4 g2 = image.eval(dc + float2(1.5, 1.5));
        half rr = image.eval(coord + distortion * 1.08).r;
        half bb = image.eval(coord + distortion * 0.93).b;
        half3 col = half3(rr, (g0.g + g1.g + g2.g) * 0.3333, bb);

        col = mix(col, half3(0.08), 0.266);

        float3 L = float3(-0.4417, -0.4417, 0.7951);
        float dotNL = clamp(dot(N, L), 0.0, 1.0);
        float i2 = inv * inv;
        float i4 = i2 * i2;
        float i8 = i4 * i4;
        float outerHighlight = i8 * i2 * 0.65 * (0.4 + 0.6 * dotNL);
        float edgeShadow = 1.0 - i4 * i2 * 0.45 * (1.0 - 0.4 * dotNL);

        col *= half(edgeShadow);
        col += half3(outerHighlight * 0.9, outerHighlight * 0.95, outerHighlight);
        col = mix(col, half3(0.92, 0.96, 1.0), 0.07 * inv);

        return mix(baseColor, half4(col, 1.0), edgeAlpha);
    }

    half4 main(float2 fragCoord) {
        half4 baseColor = image.eval(fragCoord);
        if (fragCoord.x < bounds.x || fragCoord.x > bounds.z ||
            fragCoord.y < bounds.y || fragCoord.y > bounds.w) return baseColor;

        if (inRect(fragCoord, densityBarRect)) return applyGlassEffect(fragCoord, densityBarRect, baseColor);
        if (inRect(fragCoord, barRect)) return applyGlassEffect(fragCoord, barRect, baseColor);
        if (inRect(fragCoord, searchBarRect)) return applyGlassEffect(fragCoord, searchBarRect, baseColor);
        if (inRect(fragCoord, searchFabRect)) return applyGlassEffect(fragCoord, searchFabRect, baseColor);
        if (inRect(fragCoord, pageIndicatorRect)) return applyGlassEffect(fragCoord, pageIndicatorRect, baseColor);
        if (inRect(fragCoord, selectBtnRect)) return applyGlassEffect(fragCoord, selectBtnRect, baseColor);
        if (inRect(fragCoord, collapsedFabRect)) return applyGlassEffect(fragCoord, collapsedFabRect, baseColor);
        return baseColor;
    }
""".trimIndent()

private const val RECT_COUNT = 7
private const val CACHE_SIZE = RECT_COUNT * 4 + 2

private val RECT_UNIFORMS = arrayOf(
    "barRect", "densityBarRect", "searchBarRect", "searchFabRect",
    "selectBtnRect", "collapsedFabRect", "pageIndicatorRect"
)

private class GlassEffectCache {
    val last = FloatArray(CACHE_SIZE) { Float.NaN }
    val cur = FloatArray(CACHE_SIZE)
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
}

fun Modifier.liquidGlassBackdrop(
    mainBarRect: () -> Rect,
    densityBarRect: () -> Rect,
    searchBarRect: () -> Rect,
    searchFabRect: () -> Rect,
    selectBtnRect: () -> Rect = { Rect.Zero },
    collapsedFabRect: () -> Rect = { Rect.Zero },
    pageIndicatorRect: () -> Rect = { Rect.Zero }
): Modifier = composed {
    val shader = remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try { android.graphics.RuntimeShader(LIQUID_GLASS_SHADER) } catch (e: Throwable) { null }
        } else null
    }
    val cache = remember { GlassEffectCache() }
    var failed by remember { mutableStateOf(false) }

    if (shader != null && !failed) {
        Modifier.graphicsLayer {
            try {
                val cur = cache.cur
                val rects = arrayOf(
                    mainBarRect(), densityBarRect(), searchBarRect(), searchFabRect(),
                    selectBtnRect(), collapsedFabRect(), pageIndicatorRect()
                )
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in 0 until RECT_COUNT) {
                    val r = rects[i]
                    cur[i * 4] = r.left; cur[i * 4 + 1] = r.top
                    cur[i * 4 + 2] = r.right; cur[i * 4 + 3] = r.bottom
                    if (r.width > 0f && r.height > 0f) {
                        if (r.left < minX) minX = r.left
                        if (r.top < minY) minY = r.top
                        if (r.right > maxX) maxX = r.right
                        if (r.bottom > maxY) maxY = r.bottom
                    }
                }
                val empty = minX > maxX
                // Quantize to avoid rebuilding for sub-pixel jitter
                for (i in 0 until RECT_COUNT * 4) cur[i] = Math.round(cur[i] * 2f) / 2f
                cur[RECT_COUNT * 4] = if (empty) 0f else minX - 2f
                cur[RECT_COUNT * 4 + 1] = if (empty) 0f else minY - 2f

                if (!java.util.Arrays.equals(cur, cache.last) || cache.effect == null) {
                    for (i in 0 until RECT_COUNT) {
                        shader.setFloatUniform(
                            RECT_UNIFORMS[i], cur[i * 4], cur[i * 4 + 1], cur[i * 4 + 2], cur[i * 4 + 3]
                        )
                    }
                    if (empty) shader.setFloatUniform("bounds", 0f, 0f, 0f, 0f)
                    else shader.setFloatUniform("bounds", minX - 2f, minY - 2f, maxX + 2f, maxY + 2f)
                    cache.effect = android.graphics.RenderEffect
                        .createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
                    System.arraycopy(cur, 0, cache.last, 0, CACHE_SIZE)
                }
                renderEffect = cache.effect
            } catch (e: Throwable) {
                failed = true
            }
        }
    } else {
        // Fallback (API < 33 or shader failure): translucent glass overlay with border + highlight
        Modifier.drawWithContent {
            drawContent()
            val rects = arrayOf(
                mainBarRect(), densityBarRect(), searchBarRect(), searchFabRect(),
                selectBtnRect(), collapsedFabRect(), pageIndicatorRect()
            )
            for (r in rects) drawFallbackGlass(r)
        }
    }
}

private fun DrawScope.drawFallbackGlass(r: Rect) {
    if (r.width <= 0f || r.height <= 0f) return
    val radius = CornerRadius(r.height / 2f)
    val tl = Offset(r.left, r.top)
    val sz = Size(r.width, r.height)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0x30FFFFFF), Color(0x10FFFFFF), Color(0x20141414)),
            startY = r.top, endY = r.bottom
        ),
        topLeft = tl, size = sz, cornerRadius = radius
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(GlassSpecular, GlassBarBorder, Color(0x14FFFFFF)),
            startY = r.top, endY = r.bottom
        ),
        topLeft = tl, size = sz, cornerRadius = radius, style = Stroke(1.dp.toPx())
    )
}
