package com.starfarer.companionoverlay

import com.starfarer.companionoverlay.api.ContentBlock
import com.starfarer.companionoverlay.api.Message
import com.starfarer.companionoverlay.api.MessageContent
import com.starfarer.companionoverlay.mcp.McpManager
import com.starfarer.companionoverlay.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the pure logic inside [ConversationManager].
 *
 * Focus: [ConversationManager.sanitizeToolMessages] — the orphan tool-block
 * pruning that runs before every API request. It is a private method, invoked
 * here via reflection.
 *
 * The exact semantics of `sanitizeToolMessages` (read from source):
 *  - It scans every [MessageContent.Blocks] message, collecting all tool_use
 *    ids and all tool_result ids (tool_use_id).
 *  - `matched = toolUseIds ∩ toolResultIds`.
 *  - FAST PATH: when there are no orphans (matched covers both sets fully),
 *    the *same input list reference* is returned untouched.
 *  - Otherwise each Blocks message is filtered, dropping any ToolUse whose id
 *    is not in `matched` and any ToolResult whose toolUseId is not in
 *    `matched`. Text/Image and other block types are always kept.
 *  - A message whose blocks become empty after filtering is DROPPED entirely
 *    (mapNotNull → null).
 *  - Non-Blocks messages ([MessageContent.Text], [MessageContent.RawBlocks])
 *    pass through completely untouched.
 *
 * NOTE on construction: the [ConversationManager] init block calls
 * restoreHistory() (which only touches storage.load() when
 * settings.keepDialogue is true) and startResultWatcher() (a coroutine on
 * Dispatchers.Main). We install a StandardTestDispatcher as Main so the
 * watcher launch does not crash, and stub storage.load()/settings so
 * construction is side-effect-free. The watcher's first action is a
 * delay(10_000ms) which never advances under StandardTestDispatcher, so it
 * never touches mocks during these tests.
 *
 * NOTE on trim: the history-trim logic
 * (`while (conversationHistory.size > maxMessages) removeAt(0)`) lives inside
 * the private `handleSuccess` method, which is only reachable by driving the
 * full `sendMessage` coroutine loop (network call to claudeApi, tool loop,
 * etc.). There is no clean seam to invoke it in isolation without simulating
 * the whole send path, so trim is intentionally NOT tested here — see the
 * final report for details.
 */
class ConversationManagerTest {

    private lateinit var context: android.content.Context
    private lateinit var claudeApi: ClaudeApi
    private lateinit var settings: SettingsRepository
    private lateinit var storage: ConversationStorage
    private lateinit var mcpManager: McpManager

    private lateinit var manager: ConversationManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        claudeApi = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        mcpManager = mockk(relaxed = true)

        // Keep construction cheap & side-effect-free.
        coEvery { storage.load() } returns mutableListOf()
        // restoreHistory() early-returns unless keepDialogue is true; make it false
        // so load() is never reached either way.
        every { settings.keepDialogue } returns false

        manager = ConversationManager(context, claudeApi, settings, storage, mcpManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── reflection helper ──────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun sanitize(input: List<Message>): List<Message> {
        val m = ConversationManager::class.java
            .getDeclaredMethod("sanitizeToolMessages", List::class.java)
            .apply { isAccessible = true }
        return m.invoke(manager, input) as List<Message>
    }

    // ── model builders ─────────────────────────────────────────────────────

    private fun toolUseMsg(id: String, name: String = "search"): Message = Message(
        role = "assistant",
        content = MessageContent.Blocks(
            listOf(ContentBlock.ToolUse(id = id, name = name, input = JsonObject(emptyMap())))
        )
    )

    private fun toolResultMsg(toolUseId: String, content: String = "ok"): Message = Message(
        role = "user",
        content = MessageContent.Blocks(
            listOf(ContentBlock.ToolResult(toolUseId = toolUseId, content = content))
        )
    )

    private fun textMsg(role: String, text: String): Message = Message(
        role = role,
        content = MessageContent.Text(text)
    )

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    fun `matched tool_use plus tool_result pair is preserved unchanged`() {
        val input = listOf(
            textMsg("user", "find me a thing"),
            toolUseMsg("x"),
            toolResultMsg("x"),
            textMsg("assistant", "here you go")
        )

        val out = sanitize(input)

        // No orphans → fast path returns the SAME list reference, untouched.
        assertSame("matched pairs should take the no-op fast path", input, out)
        assertEquals(4, out.size)
    }

    @Test
    fun `orphan tool_use with no matching tool_result is pruned`() {
        // tool_use "x" has no tool_result referencing it. The assistant message
        // contains only that tool_use, so after filtering it becomes empty and
        // is dropped entirely.
        val input = listOf(
            textMsg("user", "do something"),
            toolUseMsg("x")
        )

        val out = sanitize(input)

        // The orphan tool_use message is dropped; only the text message remains.
        assertEquals(1, out.size)
        assertEquals("user", out[0].role)
        assertEquals("do something", (out[0].content as MessageContent.Text).text)
    }

    @Test
    fun `orphan tool_result referencing a missing tool_use is pruned`() {
        // tool_result references "ghost" — no tool_use ever produced that id.
        val input = listOf(
            textMsg("user", "hi"),
            toolResultMsg("ghost")
        )

        val out = sanitize(input)

        // The orphan tool_result message becomes empty and is dropped.
        assertEquals(1, out.size)
        assertEquals("user", out[0].role)
        assertEquals("hi", (out[0].content as MessageContent.Text).text)
    }

    @Test
    fun `plain text only messages pass through untouched`() {
        // No tool blocks at all → toolUseIds and toolResultIds are both empty,
        // matched is empty, sizes are equal → fast path, same reference returned.
        val input = listOf(
            textMsg("user", "hello"),
            textMsg("assistant", "hi there"),
            textMsg("user", "how are you")
        )

        val out = sanitize(input)

        assertSame("text-only input should take the fast path", input, out)
        assertEquals(input, out)
    }

    @Test
    fun `message that becomes empty after pruning is dropped`() {
        // The assistant message holds ONLY an orphan tool_use; after the orphan
        // is filtered out the block list is empty, so the whole message is
        // dropped (mapNotNull → null). A separate valid pair forces the
        // non-fast-path branch so filtering actually runs.
        val orphanOnly = Message(
            role = "assistant",
            content = MessageContent.Blocks(
                listOf(ContentBlock.ToolUse(id = "orphan", name = "t", input = JsonObject(emptyMap())))
            )
        )
        val input = listOf(
            toolUseMsg("keep"),
            toolResultMsg("keep"),
            orphanOnly
        )

        val out = sanitize(input)

        // Exactly the empty-after-pruning message is gone; the valid pair stays.
        assertEquals(2, out.size)
        assertTrue("no message should still carry the orphan id",
            out.none { msg ->
                (msg.content as? MessageContent.Blocks)?.blocks?.any {
                    it is ContentBlock.ToolUse && it.id == "orphan"
                } == true
            })
        // The kept pair survives intact.
        val keptUse = out[0].content as MessageContent.Blocks
        assertEquals("keep", (keptUse.blocks[0] as ContentBlock.ToolUse).id)
        val keptResult = out[1].content as MessageContent.Blocks
        assertEquals("keep", (keptResult.blocks[0] as ContentBlock.ToolResult).toolUseId)
    }

    @Test
    fun `mixed valid pair plus orphan affects only the orphan`() {
        // One matched pair ("good"), and an orphan tool_use ("bad") that shares
        // an assistant message with a Text block. Only the orphan tool_use block
        // should be stripped; the Text block in that same message survives, so
        // the message itself is NOT dropped.
        val mixedAssistant = Message(
            role = "assistant",
            content = MessageContent.Blocks(
                listOf(
                    ContentBlock.Text("let me check two things"),
                    ContentBlock.ToolUse(id = "good", name = "a", input = JsonObject(emptyMap())),
                    ContentBlock.ToolUse(id = "bad", name = "b", input = JsonObject(emptyMap()))
                )
            )
        )
        val input = listOf(
            textMsg("user", "go"),
            mixedAssistant,
            toolResultMsg("good")   // only "good" gets a result; "bad" is orphan
        )

        val out = sanitize(input)

        // All three messages survive (none became fully empty).
        assertEquals(3, out.size)

        val assistantBlocks = (out[1].content as MessageContent.Blocks).blocks
        // Text kept, "good" tool_use kept, "bad" tool_use removed.
        assertEquals(2, assistantBlocks.size)
        assertTrue("text block should be preserved",
            assistantBlocks.any { it is ContentBlock.Text && it.text == "let me check two things" })
        assertTrue("matched tool_use 'good' should be preserved",
            assistantBlocks.any { it is ContentBlock.ToolUse && it.id == "good" })
        assertTrue("orphan tool_use 'bad' should be removed",
            assistantBlocks.none { it is ContentBlock.ToolUse && it.id == "bad" })

        // The matched tool_result is untouched.
        val resultBlocks = (out[2].content as MessageContent.Blocks).blocks
        assertEquals(1, resultBlocks.size)
        assertEquals("good", (resultBlocks[0] as ContentBlock.ToolResult).toolUseId)

        // The leading text message is untouched.
        assertEquals("go", (out[0].content as MessageContent.Text).text)
    }
}
