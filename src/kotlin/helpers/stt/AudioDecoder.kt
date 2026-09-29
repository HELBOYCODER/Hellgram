package desu.inugram.helpers.stt

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.telegram.messenger.FileLog
import java.io.File
import java.nio.ByteOrder

/**
 * Universal Android audio decoder via MediaExtractor and MediaCodec.
 * Decodes any audio/video container (OGG Opus, MP4, AAC) into 16 kHz mono PCM.
 */
object AudioDecoder {

    fun decodeToPcm16(file: File): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return null
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val outChunks = ArrayList<ShortArray>()
            var totalShorts = 0
            var isEOS = false

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 48000
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            var noOutputCounter = 0
            while (!isEOS && noOutputCounter < 200) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)
                    if (buf != null) {
                        buf.clear()
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    noOutputCounter = 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEOS = true
                    }
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shortBuf = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val shorts = ShortArray(shortBuf.remaining())
                            shortBuf.get(shorts)
                            outChunks.add(shorts)
                            totalShorts += shorts.size
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                } else {
                    noOutputCounter++
                }
            }

            val pcmAll = ShortArray(totalShorts)
            var offset = 0
            for (chunk in outChunks) {
                System.arraycopy(chunk, 0, pcmAll, offset, chunk.size)
                offset += chunk.size
            }

            return resampleTo16kMono(pcmAll, sampleRate, channels)
        } catch (e: Exception) {
            FileLog.e("AudioDecoder error", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
    }

    private fun resampleTo16kMono(input: ShortArray, srcRate: Int, channels: Int): ShortArray {
        if (input.isEmpty()) return input
        val mono: ShortArray = if (channels > 1) {
            val count = input.size / channels
            ShortArray(count) { i ->
                var sum = 0
                for (c in 0 until channels) {
                    sum += input[i * channels + c]
                }
                (sum / channels).toShort()
            }
        } else {
            input
        }
        if (srcRate == 16000) return mono

        val targetLength = ((mono.size.toLong() * 16000) / srcRate).toInt()
        val output = ShortArray(targetLength)
        val ratio = srcRate.toDouble() / 16000.0
        for (i in 0 until targetLength) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            if (idx + 1 < mono.size) {
                output[i] = (mono[idx] * (1.0 - frac) + mono[idx + 1] * frac).toInt().toShort()
            } else if (idx < mono.size) {
                output[i] = mono[idx]
            }
        }
        return output
    }
}
