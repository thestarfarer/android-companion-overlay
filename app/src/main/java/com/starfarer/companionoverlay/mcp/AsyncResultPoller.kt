package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Polls the Nexus for completed async job results (agent, round table)
 * and queues them for conversation injection.
 *
 * Exists separately from [McpManager] because polling is a feature that
 * uses transport, not a property of it. McpManager routes tool calls.
 * This class decides when to check and holds what comes back.
 */
class AsyncResultPoller(
    private val mcpManager: McpManager
) {
    companion object {
        private const val TAG = "AsyncPoll"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val TOOL_NAME = "nexus_poll_results"
    }

    private val pendingResults = ConcurrentLinkedQueue<JobResult>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun start() {
        stop()

        // Only start if the poll tool exists on a connected server
        val hasToolTool = mcpManager.getClaudeTools().any { it.name.endsWith("__$TOOL_NAME") }
        if (!hasToolTool) {
            DebugLog.log(TAG, "No $TOOL_NAME tool found, polling disabled")
            return
        }

        val qualifiedName = mcpManager.getClaudeTools()
            .first { it.name.endsWith("__$TOOL_NAME") }.name

        DebugLog.log(TAG, "Starting (${POLL_INTERVAL_MS / 1000}s interval)")

        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                try {
                    val result = mcpManager.executeTool(qualifiedName, null)
                    if (!result.isError && result.content != "No pending results.") {
                        DebugLog.log(TAG, "Got async results")
                        pendingResults.add(JobResult(content = result.content))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.log(TAG, "Poll error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun hasPendingResults(): Boolean = pendingResults.isNotEmpty()

    fun drainResults(): List<JobResult> {
        val results = mutableListOf<JobResult>()
        while (true) {
            val r = pendingResults.poll() ?: break
            results.add(r)
        }
        return results
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    data class JobResult(
        val content: String,
        val receivedAt: Long = System.currentTimeMillis()
    )
}
