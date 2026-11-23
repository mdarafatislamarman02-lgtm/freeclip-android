package app.freeclip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Where the speaker / facecam is, found by sampling a few frames. All values are 0..1,
 * x to the right and y UP (image coordinates as OpenGL sees them).
 */
class FaceInfo(
    val panX: Float,
    val camCx: Float?,
    val camCy: Float?,
    /** Width of the facecam box as a fraction of the frame (from the face size), when a facecam was found. */
    val camW: Float? = null,
    /** Aspect (w/h) of the source video, needed to size the facecam crop. */
    val aspect: Float = 16f / 9f,
) {
    /** True only when a small, steady face (a webcam overlay) was really found in several frames. */
    val hasCam: Boolean get() = camCx != null && camCy != null

    companion object {
        val NONE = FaceInfo(0.5f, null, null)
    }
}

object Faces {
    private class Hit(val x: Float, val y: Float, val size: Float)

    /**
     * Samples 8 frames across the clip. A facecam = a SMALL face that shows up at about the same spot in at least 3 frames;
     * a big face means the speaker fills the screen (then the picture just follows the face). Never throws.
     */
    fun analyze(ctx: Context, uri: Uri, startS: Double, endS: Double, aspect: Float): FaceInfo {
        val r = MediaMetadataRetriever()
        val bigX = ArrayList<Float>()
        val small = ArrayList<Hit>()
        try {
            r.setDataSource(ctx, uri)
            val w = 960
            val h = ((w / aspect).toInt() / 2) * 2
            val n8 = 8
            for (k in 0 until n8) {
                val t = ((startS + (endS - startS) * (k + 0.5) / n8) * 1e6).toLong()
                val raw = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h) ?: continue
                val b565 = raw.copy(Bitmap.Config.RGB_565, false) ?: continue
                val ew = b565.width and 1.inv()
                val bmp = if (ew != b565.width) Bitmap.createBitmap(b565, 0, 0, ew, b565.height) else b565
                val faces = arrayOfNulls<FaceDetector.Face>(6)
                val n = FaceDetector(bmp.width, bmp.height, 6).findFaces(bmp, faces)
                var best = -1f
                var bestX = 0.5f
                for (i in 0 until n) {
                    val f = faces[i] ?: continue
                    if (f.confidence() < 0.4f) continue
                    val mid = PointF()
                    f.getMidPoint(mid)
                    val x = mid.x / bmp.width
                    val y = 1f - mid.y / bmp.height
                    val size = f.eyesDistance() / bmp.width
                    if (size >= 0.07f) {
                        if (size > best) { best = size; bestX = x }
                    } else if (size >= 0.008f) {
                        small.add(Hit(x, y, size))
                    }
                }
                if (best > 0) bigX.add(bestX)
            }
        } catch (_: Throwable) {
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
        fun med(l: List<Float>): Float? = if (l.isEmpty()) null else l.sorted()[l.size / 2]
        // Largest group of small faces at about the same place.
        var bestGroup: List<Hit> = emptyList()
        for (c in small) {
            val g = small.filter { kotlin.math.abs(it.x - c.x) < 0.08f && kotlin.math.abs(it.y - c.y) < 0.10f }
            if (g.size > bestGroup.size) bestGroup = g
        }
        val panX = med(bigX) ?: 0.5f
        return if (bestGroup.size >= 3 && bigX.size < 3) {
            FaceInfo(panX, med(bestGroup.map { it.x }), med(bestGroup.map { it.y }), med(bestGroup.map { it.size }), aspect)
        } else {
            FaceInfo(panX, null, null, null, aspect)
        }
    }

    /**
     * What is really drawn: Gaming / Facecam need a facecam. If none was found (and no corner was chosen by hand),
     * the full frame is used, instead of showing a zoomed copy of the same picture twice.
     */
    fun effective(layout: LayoutMode, face: FaceInfo, corner: Int): LayoutMode =
        if ((layout == LayoutMode.GAMING || layout == LayoutMode.FACECAM) && corner == 4 && !face.hasCam) LayoutMode.FILL else layout

    /**
     * The facecam rectangle (x0, y0, x1, y1) for a corner: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right, 4 auto.
     * [boxAspect] is the shape (w/h) of the box it is drawn into, so the region is cut to the same shape and nothing is squashed.
     */
    fun camRect(corner: Int, face: FaceInfo, boxAspect: Float): FloatArray {
        val auto = corner == 4 && face.camCx != null && face.camCy != null
        val w = if (auto) (face.camW?.let { it * 7.5f } ?: 0.28f).coerceIn(0.14f, 0.40f) else 0.30f
        val h = (w * face.aspect / boxAspect).coerceIn(0.10f, 0.95f)
        val cx: Float
        val cy: Float
        when (corner) {
            0 -> { cx = w / 2; cy = 1 - h / 2 }
            1 -> { cx = 1 - w / 2; cy = 1 - h / 2 }
            2 -> { cx = w / 2; cy = h / 2 }
            3 -> { cx = 1 - w / 2; cy = h / 2 }
            else -> {
                if (auto) { cx = face.camCx!!; cy = face.camCy!! }
                else { cx = w / 2; cy = 1 - h / 2 }
            }
        }
        val x0 = (cx - w / 2).coerceIn(0f, 1f - w)
        val y0 = (cy - h / 2).coerceIn(0f, 1f - h)
        return floatArrayOf(x0, y0, x0 + w, y0 + h)
    }

    /** Fraction of the output height used by the facecam in the Gaming layout. */
    fun split(ratio: Ratio): Float = if (ratio.aspect < 0.7f) 0.36f else 0.42f
}

/** One GL effect that draws every layout (Fill / Fit / Gaming / Facecam). Used by the preview AND the export. */
@OptIn(UnstableApi::class)
class LayoutEffect(
    val mode: Int,
    val outW: Int,
    val outH: Int,
    val panX: Float,
    val cam: FloatArray,
    val split: Float,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LayoutProgram(this, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    companion object {
        fun create(layout: LayoutMode, ratio: Ratio, side: Int, face: FaceInfo, corner: Int): LayoutEffect {
            val (w, h) = ratio.size(side)
            val mode = Faces.effective(layout, face, corner)
            val box = if (mode == LayoutMode.FACECAM) ratio.aspect else ratio.aspect / Faces.split(ratio)
            return LayoutEffect(mode.shader, w, h, face.panX, Faces.camRect(corner, face, box), Faces.split(ratio))
        }
    }
}

@OptIn(UnstableApi::class)
private class LayoutProgram(private val e: LayoutEffect, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram = try {
        GlProgram(VERT, FRAG)
    } catch (ex: GlUtil.GlException) {
        throw VideoFrameProcessingException(ex)
    }
    private var inW = 1
    private var inH = 1

    init {
        program.setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = inputWidth
        inH = inputHeight
        return Size(e.outW, e.outH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uMode", e.mode.toFloat())
            program.setFloatsUniform("uIn", floatArrayOf(inW.toFloat(), inH.toFloat()))
            program.setFloatsUniform("uOut", floatArrayOf(e.outW.toFloat(), e.outH.toFloat()))
            program.setFloatUniform("uPan", e.panX)
            program.setFloatsUniform("uCam", e.cam)
            program.setFloatUniform("uSplit", e.split)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (ex: GlUtil.GlException) {
            throw VideoFrameProcessingException(ex, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (ex: GlUtil.GlException) {
            throw VideoFrameProcessingException(ex)
        }
    }

    companion object {
        private const val VERT = """
attribute vec4 aPosition;
varying vec2 vTex;
void main() {
  gl_Position = aPosition;
  vTex = aPosition.xy * 0.5 + 0.5;
}
"""
        // p = position inside the output box (0..1, y up). cover() fills a box with a region of the video,
        // cropping the overflow and sliding the window towards [pan] horizontally.
        private const val FRAG = """
precision highp float;
uniform sampler2D uTexSampler;
uniform float uMode;
uniform vec2 uIn;
uniform vec2 uOut;
uniform float uPan;
uniform vec4 uCam;
uniform float uSplit;
varying vec2 vTex;

vec2 cover(vec2 p, vec4 r, float boxAspect, float pan) {
  float ra = ((r.z - r.x) * uIn.x) / ((r.w - r.y) * uIn.y);
  vec2 f = (ra > boxAspect) ? vec2(boxAspect / ra, 1.0) : vec2(1.0, ra / boxAspect);
  float cx = clamp(pan, f.x * 0.5, 1.0 - f.x * 0.5);
  float cy = clamp(0.5, f.y * 0.5, 1.0 - f.y * 0.5);
  vec2 q = vec2(cx - f.x * 0.5 + p.x * f.x, cy - f.y * 0.5 + p.y * f.y);
  return vec2(mix(r.x, r.z, q.x), mix(r.y, r.w, q.y));
}

vec3 blurred(vec2 uv) {
  vec3 s = texture2D(uTexSampler, uv).rgb * 0.2;
  float r = 0.03;
  s += texture2D(uTexSampler, uv + vec2(r, 0.0)).rgb * 0.1;
  s += texture2D(uTexSampler, uv - vec2(r, 0.0)).rgb * 0.1;
  s += texture2D(uTexSampler, uv + vec2(0.0, r)).rgb * 0.1;
  s += texture2D(uTexSampler, uv - vec2(0.0, r)).rgb * 0.1;
  s += texture2D(uTexSampler, uv + vec2(r, r)).rgb * 0.1;
  s += texture2D(uTexSampler, uv - vec2(r, r)).rgb * 0.1;
  s += texture2D(uTexSampler, uv + vec2(r, -r)).rgb * 0.1;
  s += texture2D(uTexSampler, uv - vec2(r, -r)).rgb * 0.1;
  return s;
}

void main() {
  vec2 p = vTex;
  vec4 full = vec4(0.0, 0.0, 1.0, 1.0);
  float oa = uOut.x / uOut.y;
  vec3 c;
  if (uMode < 0.5) {
    c = texture2D(uTexSampler, cover(p, full, oa, uPan)).rgb;
  } else if (uMode < 1.5) {
    vec3 bg = blurred(cover(p, full, oa, 0.5)) * 0.55;
    float ia = uIn.x / uIn.y;
    vec2 fg;
    bool inside;
    if (ia > oa) {
      float h = oa / ia;
      fg = vec2(p.x, (p.y - (0.5 - h * 0.5)) / h);
      inside = fg.y >= 0.0 && fg.y <= 1.0;
    } else {
      float w = ia / oa;
      fg = vec2((p.x - (0.5 - w * 0.5)) / w, p.y);
      inside = fg.x >= 0.0 && fg.x <= 1.0;
    }
    c = inside ? texture2D(uTexSampler, fg).rgb : bg;
  } else if (uMode < 2.5) {
    float s = uSplit;
    if (p.y > 1.0 - s) {
      vec2 q = vec2(p.x, (p.y - (1.0 - s)) / s);
      c = texture2D(uTexSampler, cover(q, uCam, oa / s, 0.5)).rgb;
    } else {
      vec2 q = vec2(p.x, p.y / (1.0 - s));
      c = texture2D(uTexSampler, cover(q, full, oa / (1.0 - s), uPan)).rgb;
    }
  } else {
    c = texture2D(uTexSampler, cover(p, uCam, oa, 0.5)).rgb;
  }
  gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
