package com.starfarer.companionoverlay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Generates and plays cozy synthesized beep tones for voice pipeline feedback.
 * Pure sine waves, no media files. Subtle and warm.
 */
class BeepManager {

    companion object {
        private const val TAG = "BeepMgr"
        private const val SAMPLE_RATE = 44100
        private const val AMPLITUDE = 1.0f  // full amplitude
    }

    enum class Beep {
        /** Single high tone — "I'm listening" */
        READY,
        /** Ascending double-blip — "Got it, moving on" */
        STEP,
        /** Satisfying triple-rise — "All done" */
        DONE,
        /** Descending tone — sad but not jarring */
        ERROR
    }

    // Pre-generated PCM buffers
    private val buffers = mutableMapOf<Beep, ShortArray>()

    init {
        // Ready: single 880Hz, 80ms
        buffers[Beep.READY] = generateTone(880f, 80)

        // Step: 660Hz 50ms → 880Hz 50ms with tiny 10ms gap
        buffers[Beep.STEP] = concatenate(
            generateTone(660f, 50),
            generateSilence(10),
            generateTone(880f, 50)
        )

        // Done: 660→880→1100Hz, 50ms each with 10ms gaps
        buffers[Beep.DONE] = concatenate(
            generateTone(660f, 50),
            generateSilence(10),
            generateTone(880f, 50),
            generateSilence(10),
            generateTone(1100f, 50)
        )

        // Error: 440Hz→330Hz descending, 100ms total (smooth sweep)
        buffers[Beep.ERROR] = generateSweep(440f, 330f, 120)
    }

    fun play(beep: Beep) {
        val pcm = buffers[beep] ?: return
        DebugLog.log(TAG, "Playing beep: $beep (${pcm.size} samples)")
        try {
            val bufferSize = pcm.size * 2  // 16-bit = 2 bytes per sample
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    t?.release()
                }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            track.play()
        } catch (e: Exception) {
            DebugLog.log(TAG, "Beep failed: ${e.message}")
        }
    }

    /** Generate a sine wave tone at given frequency and duration. Applies fade in/out to avoid clicks. */
    private fun generateTone(freqHz: Float, durationMs: Int): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs / 1000f).toInt()
        val samples = ShortArray(numSamples)
        val fadeLen = minOf(numSamples / 4, (SAMPLE_RATE * 0.005f).toInt()) // 5ms fade or 1/4 of tone

        for (i in 0 until numSamples) {
            val t = i.toFloat() / SAMPLE_RATE
            var value = Math.sin(2.0 * Math.PI * freqHz * t).toFloat() * AMPLITUDE

            // Fade envelope
            if (i < fadeLen) {
                value *= i.toFloat() / fadeLen
            } else if (i > numSamples - fadeLen) {
                value *= (numSamples - i).toFloat() / fadeLen
            }

            samples[i] = (value * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    /** Generate a frequency sweep (for the sad error beep). */
    private fun generateSweep(startHz: Float, endHz: Float, durationMs: Int): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs / 1000f).toInt()
        val samples = ShortArray(numSamples)
        val fadeLen = minOf(numSamples / 4, (SAMPLE_RATE * 0.005f).toInt())

        var phase = 0.0
        for (i in 0 until numSamples) {
            val frac = i.toFloat() / numSamples
            val freq = startHz + (endHz - startHz) * frac
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE
            var value = Math.sin(phase).toFloat() * AMPLITUDE

            if (i < fadeLen) {
                value *= i.toFloat() / fadeLen
            } else if (i > numSamples - fadeLen) {
                value *= (numSamples - i).toFloat() / fadeLen
            }

            samples[i] = (value * Short.MAX_VALUE).toInt().toShort()
        }
        return samples
    }

    private fun generateSilence(durationMs: Int): ShortArray {
        return ShortArray((SAMPLE_RATE * durationMs / 1000f).toInt())
    }

    private fun concatenate(vararg arrays: ShortArray): ShortArray {
        val total = arrays.sumOf { it.size }
        val result = ShortArray(total)
        var offset = 0
        for (arr in arrays) {
            arr.copyInto(result, offset)
            offset += arr.size
        }
        return result
    }
}
