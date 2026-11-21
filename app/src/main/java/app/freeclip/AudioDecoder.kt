package app.freeclip

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes the audio track of a video to 16 kHz mono 16-bit PCM, chunk by chunk (low memory). */
object AudioDecoder {
    const val RATE = 16000

    fun decode(
        ctx: Context,
        uri: Uri,
        durationUs: Long,
        onPcm: (ShortArray) -> Unit,
        onProgress: (Float) -> Unit,
        checkActive: () -> Unit,
    ) {
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

        try {
            while (!outDone) {
                checkActive()
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val shorts = ShortArray(sb.remaining())
                        sb.get(shorts)

                        val frames = shorts.size / ch
                        val step = sr / RATE.toDouble()
                        val out = ShortArray(((frames - carry) / step).toInt() + 2)
                        var n = 0
                        var pos = carry
                        while (pos < frames) {
                            val a = pos.toInt()
                            val b = minOf(frames, maxOf(a + 1, (pos + step).toInt()))
                            var acc = 0L
                            var cnt = 0
                            for (f in a until b) {
                                for (c in 0 until ch) {
                                    acc += shorts[f * ch + c]
                                    cnt++
                                }
                            }
                            out[n++] = (acc / cnt).toShort()
                            pos += step
                        }
                        carry = pos - frames
                        if (n > 0) onPcm(out.copyOf(n))
                        if (durationUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
    }
}
