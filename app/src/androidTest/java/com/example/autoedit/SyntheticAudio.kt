package com.veycad.app

import java.io.File
import kotlin.math.PI
import kotlin.math.sin

internal object SyntheticAudio {
    const val RATE = 16_000
    fun writeWave(file: File, seconds: Int = 16): File {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            fun le16(value: Int) { output.write(value and 255); output.write(value ushr 8 and 255) }
            fun le32(value: Int) { le16(value); le16(value ushr 16) }
            val count = RATE * seconds
            output.write("RIFF".toByteArray()); le32(36 + count * 2)
            output.write("WAVEfmt ".toByteArray()); le32(16); le16(1); le16(1)
            le32(RATE); le32(RATE * 2); le16(2); le16(16)
            output.write("data".toByteArray()); le32(count * 2)
            repeat(count) { index ->
                // Silent intro, followed by an independently known 120 BPM score.
                val local = index - RATE * 2
                val phase = if (local < 0) -1 else local % (RATE / 2)
                val value = if (phase in 0 until RATE / 10)
                    sin(2 * PI * 100 * phase / RATE) * (1.0 - phase.toDouble() / (RATE / 10)) * .8 else 0.0
                le16((value * 32_767).toInt())
            }
        }
        return file
    }
}
