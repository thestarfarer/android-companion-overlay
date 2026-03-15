package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Polls all connected MCP servers for completed async job results
 * and queues them for conversation injection.
 *
 * Results are consumed non-destructively via [peekResults] and only
 * removed from the queue via [drainResults] after the caller confirms
 * they were successfully delivered.
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

        val pollTools = mcpManager.getClaudeTools()
            .filter { it.name.endsWith("__$TOOL_NAME") }
            .map { it.name }

        if (pollTools.isEmpty()) {
            DebugLog.log(TAG, "No $TOOL_NAME tool found, polling disabled")
            return
        }

        DebugLog.log(TAG, "Starting (${POLL_INTERVAL_MS / 1000}s interval, ${pollTools.size} server(s))")

        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                for (qualifiedName in pollTools) {
                    try {
                        val result = mcpManager.executeTool(qualifiedName, null)
                        if (!result.isError && result.content != "No pending results.") {
                            DebugLog.log(TAG, "Got async results from ${qualifiedName.substringBefore("__")}")
                            pendingResults.add(JobResult(content = result.content))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DebugLog.log(TAG, "Poll error on $qualifiedName: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun hasPendingResults(): Boolean = pendingResults.isNotEmpty()

    fun peekResults(): List<JobResult> = pendingResults.toList()

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
