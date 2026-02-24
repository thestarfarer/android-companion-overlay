package com.starfarer.companionoverlay

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 wrapper. Expects 16kHz mono PCM in 512-sample windows.
 * Returns speech probability [0..1] for each window.
 *
 * Critical: V5 requires 64 samples of context from the previous chunk
 * prepended to each 512-sample window (total 576 samples fed to model).
 * The OnnxWrapper in the official Python code does this automatically.
 *
 * Usage:
 *   val vad = SileroVadDetector(context)
 *   vad.reset()          // call before each recording session
 *   val prob = vad.detect(shortArray512)  // returns 0.0..1.0
 *   vad.close()          // release resources
 */
class SileroVadDetector(context: Context) {

    companion object {
        private const val TAG = "SileroVAD"
        const val WINDOW_SAMPLES = 512  // user-facing chunk size at 16kHz
        private const val CONTEXT_SIZE = 64  // V5 context overlap
        private const val INPUT_SIZE = WINDOW_SAMPLES + CONTEXT_SIZE  // 576 samples to model
        private const val SAMPLE_RATE = 16000L
        private const val STATE_DIM = 128
        private const val STATE_LAYERS = 2
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // LSTM hidden state — persists across calls within a session
    private var state: Array<Array<FloatArray>>
    // Context buffer — last 64 samples from previous call
    private var contextBuffer: FloatArray

    init {
        val modelBytes = context.assets.open("silero_vad.onnx").readBytes()
        session = env.createSession(modelBytes)
        state = freshState()
        contextBuffer = FloatArray(CONTEXT_SIZE)
        DebugLog.log(TAG, "Model loaded (${modelBytes.size / 1024}KB), context=$CONTEXT_SIZE, input=$INPUT_SIZE")
    }

    private fun freshState(): Array<Array<FloatArray>> {
        return Array(STATE_LAYERS) { Array(1) { FloatArray(STATE_DIM) } }
    }

    /** Reset LSTM state and context buffer. Call before each new recording session. */
    fun reset() {
        state = freshState()
        contextBuffer = FloatArray(CONTEXT_SIZE)
    }

    /**
     * Run VAD on a 512-sample window of 16kHz PCM.
     * Internally prepends 64 samples of context (matching official OnnxWrapper).
     * @return speech probability [0.0 .. 1.0]
     */
    fun detect(samples: ShortArray): Float {
        require(samples.size == WINDOW_SAMPLES) {
            "Expected $WINDOW_SAMPLES samples, got ${samples.size}"
        }

        // Convert shorts to normalized floats
        val currentFloat = FloatArray(WINDOW_SAMPLES) { samples[it] / 32768.0f }

        // Prepend context from previous chunk (V5 requirement)
        val inputWithContext = FloatArray(INPUT_SIZE)
        System.arraycopy(contextBuffer, 0, inputWithContext, 0, CONTEXT_SIZE)
        System.arraycopy(currentFloat, 0, inputWithContext, CONTEXT_SIZE, WINDOW_SAMPLES)

        // Save last 64 samples as context for next call
        System.arraycopy(inputWithContext, INPUT_SIZE - CONTEXT_SIZE, contextBuffer, 0, CONTEXT_SIZE)

        // Create tensors
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputWithContext),
            longArrayOf(1, INPUT_SIZE.toLong())
        )
        val stateTensor = OnnxTensor.createTensor(env, state)
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(SAMPLE_RATE)),
            longArrayOf()  // scalar
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "state" to stateTensor,
            "sr" to srTensor
        )

        val result = session.run(inputs)

        // Extract speech probability
        @Suppress("UNCHECKED_CAST")
        val output = (result[0].value as Array<FloatArray>)[0][0]

        // Update LSTM state for next call
        @Suppress("UNCHECKED_CAST")
        state = result[1].value as Array<Array<FloatArray>>

        inputTensor.close()
        stateTensor.close()
        srTensor.close()
        result.close()

        return output
    }

    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        session.close()
        env.close()
    }
}
