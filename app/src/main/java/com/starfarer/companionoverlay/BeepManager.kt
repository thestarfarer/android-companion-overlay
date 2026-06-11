package com.starfarer.companionoverlay

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Synthesized beep tones played through a persistent SoundPool.
 *
 * Uses USAGE_ASSISTANCE_SONIFICATION — routes through A2DP like media
 * but on its own mixer lane, so TTS QUEUE_FLUSH can't stomp it.
 */
class BeepManager(private val context: Context) {

    companion object {
        private const val TAG = "BeepMgr"
        private const val SAMPLE_RATE = 44100
        private const val AMPLITUDE = 1.0f
    }

    enum class Beep { READY, STEP, DONE, ERROR, QUEUE }

    private val soundPool: SoundPool
    private val soundIds = java.util.concurrent.ConcurrentHashMap<Beep, Int>()

    // Temp-WAV bookkeeping: each file is deleted as soon as ITS load completes
    // (success or failure). The old all-or-nothing pass left every file in the
    // cache forever if a single load failed.
    private val fileLock = Any()
    private val pendingFiles = HashMap<Int, File>()
    private val completedIds = HashSet<Int>()
    private var loaded = 0

    init {
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()

        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0 && ++loaded == Beep.entries.size) {
                DebugLog.log(TAG, "All beeps loaded")
            }
            val file = synchronized(fileLock) {
                completedIds.add(sampleId)
                pendingFiles.remove(sampleId)
            }
            file?.delete()
        }

        // PCM synthesis + WAV writes are disk I/O, and this is constructed by
        // DI during service onCreate — keep it off the main thread.
        Thread({
            mapOf(
                Beep.READY to generateTone(880f, 80),
                Beep.STEP to concatenate(generateTone(660f, 50), generateSilence(10), generateTone(880f, 50)),
                Beep.DONE to concatenate(generateTone(660f, 50), generateSilence(10), generateTone(880f, 50), generateSilence(10), generateTone(1100f, 50)),
                Beep.ERROR to generateSweep(440f, 330f, 120),
                Beep.QUEUE to concatenate(generateTone(1047f, 45), generateSilence(15), generateTone(784f, 55))
            ).forEach { (beep, pcm) ->
                val file = writeWav(pcm, "beep_${beep.name.lowercase()}.wav")
                val id = soundPool.load(file.absolutePath, 1)
                soundIds[beep] = id
                synchronized(fileLock) {
                    // The load callback may already have fired for this id.
                    if (completedIds.contains(id)) file.delete() else pendingFiles[id] = file
                }
            }
        }, "beep-init").start()
    }

    fun play(beep: Beep) {
        val id = soundIds[beep] ?: return
        val stream = soundPool.play(id, 1.0f, 1.0f, 1, 0, 1.0f)
        DebugLog.log(TAG, "Playing $beep (soundId=$id, stream=$stream)")
    }

    fun release() = soundPool.release()

    // ── WAV bridge ──

    private fun writeWav(pcm: ShortArray, filename: String): File {
        val file = File(context.cacheDir, filename)
        val dataLen = pcm.size * 2
        RandomAccessFile(file, "rw").use { raf ->
            raf.writeBytes("RIFF"); raf.writeIntLE(36 + dataLen); raf.writeBytes("WAVE")
            raf.writeBytes("fmt "); raf.writeIntLE(16); raf.writeShortLE(1); raf.writeShortLE(1)
            raf.writeIntLE(SAMPLE_RATE); raf.writeIntLE(SAMPLE_RATE * 2); raf.writeShortLE(2); raf.writeShortLE(16)
            raf.writeBytes("data"); raf.writeIntLE(dataLen)
            val buf = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) buf.putShort(s)
            raf.write(buf.array())
        }
        return file
    }

    private fun RandomAccessFile.writeIntLE(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun RandomAccessFile.writeShortLE(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    // ── Tone generation ──

    private fun generateTone(freqHz: Float, durationMs: Int): ShortArray {
        val n = (SAMPLE_RATE * durationMs / 1000f).toInt()
        val fade = minOf(n / 4, (SAMPLE_RATE * 0.005f).toInt())
        return ShortArray(n) { i ->
            var v = Math.sin(2.0 * Math.PI * freqHz * i / SAMPLE_RATE).toFloat() * AMPLITUDE
            if (i < fade) v *= i.toFloat() / fade
            else if (i > n - fade) v *= (n - i).toFloat() / fade
            (v * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun generateSweep(startHz: Float, endHz: Float, durationMs: Int): ShortArray {
        val n = (SAMPLE_RATE * durationMs / 1000f).toInt()
        val fade = minOf(n / 4, (SAMPLE_RATE * 0.005f).toInt())
        var phase = 0.0
        return ShortArray(n) { i ->
            val freq = startHz + (endHz - startHz) * i.toFloat() / n
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE
            var v = Math.sin(phase).toFloat() * AMPLITUDE
            if (i < fade) v *= i.toFloat() / fade
            else if (i > n - fade) v *= (n - i).toFloat() / fade
            (v * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun generateSilence(ms: Int) = ShortArray((SAMPLE_RATE * ms / 1000f).toInt())

    private fun concatenate(vararg a: ShortArray): ShortArray {
        val r = ShortArray(a.sumOf { it.size }); var o = 0
        for (arr in a) { arr.copyInto(r, o); o += arr.size }
        return r
    }
}
