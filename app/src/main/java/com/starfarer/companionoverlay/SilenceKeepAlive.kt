package com.starfarer.companionoverlay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Near-silent audio stream that keeps the Bluetooth A2DP codec warm.
 *
 * Without active audio, A2DP sinks power down and clip the leading
 * edge of the next sound. This writes amplitude 1/32767 through
 * USAGE_MEDIA — inaudible but enough to hold the pipe open.
 */
class SilenceKeepAlive {

    companion object {
        private const val TAG = "BtKeepAlive"
        private const val SAMPLE_RATE = 44100
        private const val NEAR_ZERO: Short = 1
    }

    private var track: AudioTrack? = null

    @Volatile
    var isActive = false
        private set

    fun start() {
        if (isActive) return
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val silence = ShortArray(bufferSize / 2) { NEAR_ZERO }
            t.play()
            isActive = true
            track = t

            Thread {
                try { while (isActive) { if (t.write(silence, 0, silence.size) < 0) break } }
                catch (_: Exception) {}
            }.start()

            DebugLog.log(TAG, "Started")
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed: ${e.message}")
        }
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        track = null
        DebugLog.log(TAG, "Stopped")
    }
}
