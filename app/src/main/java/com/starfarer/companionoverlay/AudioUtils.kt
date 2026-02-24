package com.starfarer.companionoverlay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Shared audio utilities for recording pipelines.
 * Extracted from GeminiSpeechRecognizer and CarVoiceRecorder to eliminate duplication.
 */
object AudioUtils {

    const val SAMPLE_RATE = 16000

    /**
     * Wrap raw PCM data (16-bit mono, 16kHz) in a WAV container.
     */
    fun pcmToWav(pcmData: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(44 + pcmData.size)
        val dos = DataOutputStream(bos)

        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = pcmData.size

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeIntLE(36 + dataSize)
        dos.writeBytes("WAVE")

        // fmt subchunk
        dos.writeBytes("fmt ")
        dos.writeIntLE(16)
        dos.writeShortLE(1) // PCM
        dos.writeShortLE(numChannels)
        dos.writeIntLE(SAMPLE_RATE)
        dos.writeIntLE(byteRate)
        dos.writeShortLE(blockAlign)
        dos.writeShortLE(bitsPerSample)

        // data subchunk
        dos.writeBytes("data")
        dos.writeIntLE(dataSize)
        dos.write(pcmData)

        dos.flush()
        return bos.toByteArray()
    }

    /**
     * Convert a ByteArray of little-endian 16-bit PCM to ShortArray.
     * Used to feed CarAudioRecord output (bytes) to Silero VAD (shorts).
     */
    fun bytesToShorts(bytes: ByteArray, bytesRead: Int): ShortArray {
        val count = bytesRead / 2
        val shorts = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            shorts[i] = ((hi shl 8) or lo).toShort()
        }
        return shorts
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}
