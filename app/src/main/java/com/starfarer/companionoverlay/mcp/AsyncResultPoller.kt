package com.starfarer.companionoverlay.mcp

import com.starfarer.companionoverlay.DebugLog
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Polls all connected MCP servers for completed async job results
 * and queues them for conversation injection.
 *
 * Consumption protocol: callers [peekResults], inject them into a request,
 * and call [confirmDelivered] with that exact list only after the response
 * arrives. Results that fail the round trip — and results that arrived
 * *during* it — stay queued for the next opportunity. (The old drain-all
 * approach lost both kinds.)
 *
 * Polling slows down after [IDLE_AFTER_MS] without conversation activity
 * and snaps back on the next exchange — the 30s heartbeat only decides
 * whether an actual network poll is due.
 */
class AsyncResultPoller(
    private val mcpManager: McpManager,
    private val lastActivity: () -> Long
) {
    companion object {
        private const val TAG = "AsyncPoll"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val IDLE_AFTER_MS = 30 * 60_000L
        private const val IDLE_POLL_INTERVAL_MS = 5 * 60_000L
        private const val TOOL_NAME = "nexus_poll_results"
        /** Keep the newest results if nothing consumes the queue for a long time. */
        private const val MAX_QUEUED = 20
    }

    private val pendingResults = ConcurrentLinkedQueue<JobResult>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun start() {
        stop()

        DebugLog.log(TAG, "Starting (${POLL_INTERVAL_MS / 1000}s active / ${IDLE_POLL_INTERVAL_MS / 1000}s idle)")

        pollJob = scope.launch {
            var lastPollAt = 0L
            while (isActive) {
                delay(POLL_INTERVAL_MS)

                val idleMs = System.currentTimeMillis() - lastActivity()
                val interval = if (idleMs > IDLE_AFTER_MS) IDLE_POLL_INTERVAL_MS else POLL_INTERVAL_MS
                if (System.currentTimeMillis() - lastPollAt < interval) continue
                lastPollAt = System.currentTimeMillis()

                // Re-resolve per cycle — servers that connect after start()
                // used to be invisible until the poller was recreated.
                val pollTools = mcpManager.getClaudeTools()
                    .filter { it.name.endsWith("__$TOOL_NAME") }
                    .map { it.name }

                for (qualifiedName in pollTools) {
                    try {
                        val result = mcpManager.executeTool(qualifiedName, null)
                        // KNOWN WEAK POINT (accepted): empty-queue detection is an
                        // exact string match on the server's prose. If the Nexus
                        // server rephrases "No pending results.", every empty poll
                        // becomes an injected "result". Contract with the server.
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

                var dropped = 0
                while (pendingResults.size > MAX_QUEUED) {
                    pendingResults.poll()
                    dropped++
                }
                if (dropped > 0) DebugLog.log(TAG, "Queue over $MAX_QUEUED — dropped $dropped oldest result(s)")
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun hasPendingResults(): Boolean = pendingResults.isNotEmpty()

    fun peekResults(): List<JobResult> = pendingResults.toList()

    /**
     * Remove exactly [delivered] from the queue. Anything that arrived after
     * the peek stays queued for the next injection opportunity.
     */
    fun confirmDelivered(delivered: List<JobResult>) {
        for (result in delivered) pendingResults.remove(result)
    }

    /** Carry over undelivered results from a previous poller instance. */
    fun seed(results: List<JobResult>) {
        pendingResults.addAll(results)
        if (results.isNotEmpty()) DebugLog.log(TAG, "Seeded ${results.size} carried-over result(s)")
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
