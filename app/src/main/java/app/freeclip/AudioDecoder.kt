package app.freeclip

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/** Decodes a video's audio to a 16 kHz mono 16-bit PCM file, also measuring loudness per half second. */
object AudioDecoder {
    const val RATE = 16000
    const val HOP = 8000 // samples per loudness step = 0.5 s

    /** [rms] is raw loudness per HOP, starting at [baseS] seconds of video time. */
    class Result(val samples: Long, val baseS: Double, val rms: FloatArray)

    fun decodeToFile(
        ctx: Context,
        uri: Uri,
        startUs: Long,
        limitUs: Long, // stop after this video time; 0 = to the end
        spanUs: Long,  // length being decoded, only used for progress
        out: File,
        onProgress: (Float) -> Unit,
        checkActive: () -> Unit,
    ): Result {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = i
                break
            }
        }
        if (track < 0) {
            ex.release()
            throw IllegalStateException("This video has no audio track, so there is nothing to transcribe.")
        }
        ex.selectTrack(track)
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inDone = false
        var outDone = false
        var carry = 0.0
        var baseUs = -1L
        var written = 0L
        val rms = ArrayList<Float>()
        var acc = 0.0
        var cnt = 0

        try {
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { os ->
                while (!outDone) {
                    checkActive()
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(0)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            val t = ex.sampleTime
                            if (n < 0 || (limitUs > 0 && t > limitUs)) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, t, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        if (info.size > 0 && info.presentationTimeUs >= startUs - 20_000) {
                            if (baseUs < 0) baseUs = info.presentationTimeUs
                            val ob = codec.getOutputBuffer(o)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val shorts = ShortArray(sb.remaining())
                            sb.get(shorts)

                            val frames = shorts.size / ch
                            val step = sr / RATE.toDouble()
                            val outArr = ShortArray(max(2, ((frames - carry) / step).toInt() + 2))
                            var n = 0
                            var pos = carry
                            while (pos < frames) {
                                val a = pos.toInt()
                                val b = minOf(frames, maxOf(a + 1, (pos + step).toInt()))
                                var s = 0L
                                var c = 0
                                for (f in a until b) {
                                    for (k in 0 until ch) {
                                        s += shorts[f * ch + k]
                                        c++
                                    }
                                }
                                outArr[n++] = (s / max(c, 1)).toShort()
                                pos += step
                            }
                            carry = pos - frames

                            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until n) {
                                val v = outArr[k]
                                bb.putShort(v)
                                val f = v / 32768.0
                                acc += f * f
                                cnt++
                                if (cnt == HOP) {
                                    rms.add(sqrt(acc / cnt).toFloat())
                                    acc = 0.0
                                    cnt = 0
                                }
                            }
                            os.write(bb.array(), 0, n * 2)
                            written += n
                            if (spanUs > 0) {
                                onProgress(((info.presentationTimeUs - startUs).toFloat() / spanUs).coerceIn(0f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
        val base = if (baseUs < 0) startUs else baseUs
        return Result(written, base / 1e6, rms.toFloatArray())
    }
}
