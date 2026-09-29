package desu.inugram.helpers.stt

import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin FLAC stream encoder for 16 kHz mono 16-bit audio.
 * Adapted from Sokhan (app.sokhan.android.stt.Flac).
 */
object FlacEncoder {
    const val FLAC_BLOCK_SAMPLES = 4096
    const val FLAC_SAMPLE_RATE = 16000

    private fun crc8(data: ByteArray, len: Int): Byte {
        var crc = 0
        for (i in 0 until len) {
            var c = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1
            }
            crc = c and 0xFF
        }
        return crc.toByte()
    }

    private fun crc16(data: ByteArray, len: Int): Short {
        var crc = 0
        for (i in 0 until len) {
            var c = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1
            }
            crc = c and 0xFFFF
        }
        return crc.toShort()
    }

    private class BitWriter : ByteArrayOutputStream(64 * 1024) {
        private var byte = 0
        private var bits = 0

        fun write(value: Long, count: Int) {
            for (i in count - 1 downTo 0) {
                byte = (byte shl 1) or (((value shr i) and 1L).toInt())
                bits++
                if (bits == 8) {
                    super.write(byte)
                    byte = 0
                    bits = 0
                }
            }
        }

        fun flushBytes() {
            if (bits > 0) {
                super.write(byte shl (8 - bits))
                byte = 0
                bits = 0
            }
        }

        fun crc8SoFar(): Byte = crc8(buf, count)
        fun crc16SoFar(): Short = crc16(buf, count)
    }

    private fun writeUtf8Number(w: BitWriter, value: Long) {
        val v = value and 0xFFFF_FFFFL
        when {
            v < 0x80L -> w.write(v, 8)
            v < 0x800L -> {
                w.write(0xC0L or (v shr 6), 8)
                w.write(0x80L or (v and 0x3F), 8)
            }
            v < 0x1_0000L -> {
                w.write(0xE0L or (v shr 12), 8)
                w.write(0x80L or ((v shr 6) and 0x3F), 8)
                w.write(0x80L or (v and 0x3F), 8)
            }
            v < 0x20_0000L -> {
                w.write(0xF0L or (v shr 18), 8)
                w.write(0x80L or ((v shr 12) and 0x3F), 8)
                w.write(0x80L or ((v shr 6) and 0x3F), 8)
                w.write(0x80L or (v and 0x3F), 8)
            }
            else -> {
                w.write(0xF8L or (v shr 24), 8)
                w.write(0x80L or ((v shr 18) and 0x3F), 8)
                w.write(0x80L or ((v shr 12) and 0x3F), 8)
                w.write(0x80L or ((v shr 6) and 0x3F), 8)
                w.write(0x80L or (v and 0x3F), 8)
            }
        }
    }

    private fun encodeFrame(samples: ShortArray, frameNumber: Long): ByteArray {
        val w = BitWriter()
        w.write(0b11_1111_1111_1110L, 14) // frame sync
        w.write(0, 1)
        w.write(0, 1)
        w.write(0b1100L, 4) // block size: 4096
        w.write(0b0101L, 4) // rate: 16000 Hz
        w.write(0b0000L, 4) // mono
        w.write(0b100L, 3)  // 16 bits
        w.write(0, 1)
        writeUtf8Number(w, frameNumber)
        val headerCrc = w.crc8SoFar()
        w.write(headerCrc.toLong() and 0xFF, 8)

        // VERBATIM subframe
        w.write(0, 1)
        w.write(0b000001L, 6)
        w.write(0, 1)
        for (s in samples) {
            w.write(s.toLong() and 0xFFFF, 16)
        }
        w.flushBytes()

        val frameCrc = w.crc16SoFar()
        w.write((frameCrc.toLong() shr 8) and 0xFF, 8)
        w.write(frameCrc.toLong() and 0xFF, 8)
        w.flushBytes()
        return w.toByteArray()
    }

    fun encodePcm16(samples: ShortArray): ByteArray {
        val pending = ByteArrayOutputStream(samples.size + 1024)
        fun bytes(vararg v: Int) = ByteArray(v.size) { i -> v[i].toByte() }
        pending.write("fLaC".toByteArray(Charsets.US_ASCII), 0, 4)

        // STREAMINFO (34 bytes)
        val info = ByteArray(34)
        info[0] = (FLAC_BLOCK_SAMPLES shr 8).toByte()
        info[1] = FLAC_BLOCK_SAMPLES.toByte()
        info[2] = (FLAC_BLOCK_SAMPLES shr 8).toByte()
        info[3] = FLAC_BLOCK_SAMPLES.toByte()
        val tail = (FLAC_SAMPLE_RATE.toLong() shl 44) or (15L shl 36) or (samples.size.toLong() and 0xF_FFFF_FFFFL)
        for (i in 0 until 8) info[10 + i] = (tail shr (8 * (7 - i))).toByte()
        pending.write(bytes(0x80, 0, 0, 34), 0, 4)
        pending.write(info, 0, info.size)

        val frame = ShortArray(FLAC_BLOCK_SAMPLES)
        var offset = 0
        var frameNumber = 0L

        while (offset < samples.size) {
            val take = minOf(FLAC_BLOCK_SAMPLES, samples.size - offset)
            System.arraycopy(samples, offset, frame, 0, take)
            if (take < FLAC_BLOCK_SAMPLES) {
                for (i in take until FLAC_BLOCK_SAMPLES) frame[i] = 0
            }
            val encoded = encodeFrame(frame, frameNumber)
            pending.write(encoded, 0, encoded.size)
            frameNumber++
            offset += take
        }

        return pending.toByteArray()
    }
}
