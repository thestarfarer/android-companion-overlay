package com.starfarer.companionoverlay.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MessageContentSerializer] and the polymorphic [MessageContent]
 * round-trips.
 *
 * The serializer maps Claude's polymorphic `content` field:
 * - [MessageContent.Text] <-> bare JSON string
 * - [MessageContent.Blocks] <-> JSON array of typed content blocks
 * - [MessageContent.RawBlocks] -> JsonArray verbatim (encode only)
 *
 * The [Json] instance mirrors production usage (ignoreUnknownKeys + encodeDefaults).
 */
class MessageContentSerializerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. Text serializes to a bare string
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Text content serializes to a bare JSON string not an object or array`() {
        val message = Message(role = "user", content = MessageContent.Text("hello"))

        val encoded = json.encodeToString(message)
        val contentElement = json.parseToJsonElement(encoded).jsonObjectContent()

        assertTrue(
            "content should be a JsonPrimitive, was ${contentElement::class.simpleName}",
            contentElement is JsonPrimitive
        )
        val primitive = contentElement as JsonPrimitive
        assertTrue("content primitive should be a string", primitive.isString)
        assertEquals("hello", primitive.content)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. Text round-trips
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Text content round-trips through encode and decode`() {
        val original = Message(role = "user", content = MessageContent.Text("hello world"))

        val decoded = json.decodeFromString<Message>(json.encodeToString(original))

        assertEquals(original, decoded)
        assertTrue(decoded.content is MessageContent.Text)
        assertEquals("hello world", (decoded.content as MessageContent.Text).text)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. Blocks with a Text block -> JSON array; round-trips
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Blocks with a Text block serializes content to a JSON array and round-trips`() {
        val original = Message(
            role = "assistant",
            content = MessageContent.Blocks(listOf(ContentBlock.Text("a block")))
        )

        val encoded = json.encodeToString(original)
        val contentElement = json.parseToJsonElement(encoded).jsonObjectContent()
        assertTrue(
            "content should be a JsonArray, was ${contentElement::class.simpleName}",
            contentElement is JsonArray
        )
        assertTrue("type discriminator should be present", encoded.contains("\"type\":\"text\""))

        val decoded = json.decodeFromString<Message>(encoded)
        assertEquals(original, decoded)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. Blocks with Image + Text (screenshot-style) round-trips with defaults
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Blocks with Image and Text round-trips and image source defaults appear in JSON`() {
        val original = Message(
            role = "user",
            content = MessageContent.Blocks(
                listOf(
                    ContentBlock.Image(ImageSource(data = "BASE64DATA")),
                    ContentBlock.Text("look at this")
                )
            )
        )

        val encoded = json.encodeToString(original)
        assertTrue("default image type should be encoded", encoded.contains("\"type\":\"base64\""))
        assertTrue(
            "default media_type should be encoded",
            encoded.contains("\"media_type\":\"image/jpeg\"")
        )
        assertTrue("image block discriminator present", encoded.contains("\"type\":\"image\""))

        val decoded = json.decodeFromString<Message>(encoded)
        assertEquals(original, decoded)

        val blocks = (decoded.content as MessageContent.Blocks).blocks
        val image = blocks.filterIsInstance<ContentBlock.Image>().single()
        assertEquals("base64", image.source.type)
        assertEquals("image/jpeg", image.source.mediaType)
        assertEquals("BASE64DATA", image.source.data)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5. Decoding a bare string content -> Text
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `decoding a bare string content yields MessageContent Text`() {
        val raw = """{"role":"user","content":"just text"}"""

        val decoded = json.decodeFromString<Message>(raw)

        assertTrue(decoded.content is MessageContent.Text)
        assertEquals("just text", (decoded.content as MessageContent.Text).text)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 6. Decoding an array content -> Blocks
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `decoding an array content yields MessageContent Blocks`() {
        val raw = """{"role":"user","content":[{"type":"text","text":"hi"}]}"""

        val decoded = json.decodeFromString<Message>(raw)

        assertTrue(decoded.content is MessageContent.Blocks)
        val blocks = (decoded.content as MessageContent.Blocks).blocks
        assertEquals(1, blocks.size)
        assertEquals("hi", (blocks.single() as ContentBlock.Text).text)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 7. Decoding a JSON object content -> fallback Text("")
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `decoding a JSON object content yields fallback empty Text`() {
        val raw = """{"role":"user","content":{"foo":1}}"""

        val decoded = json.decodeFromString<Message>(raw)

        assertTrue(decoded.content is MessageContent.Text)
        assertEquals("", (decoded.content as MessageContent.Text).text)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 8. textContent() for each variant
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `textContent extracts plain text for Text variant`() {
        val content = MessageContent.Text("plain")
        assertEquals("plain", content.textContent())
    }

    @Test
    fun `textContent concatenates only text blocks for Blocks variant`() {
        val content = MessageContent.Blocks(
            listOf(
                ContentBlock.Text("foo"),
                ContentBlock.Image(ImageSource(data = "IMG")),
                ContentBlock.Text("bar")
            )
        )
        assertEquals("foobar", content.textContent())
    }

    @Test
    fun `textContent extracts only text objects for RawBlocks variant`() {
        val rawArray: JsonArray = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "kept")
            })
            add(buildJsonObject {
                put("type", "tool_use")
                put("name", "ignored")
            })
        }
        val content = MessageContent.RawBlocks(rawArray)
        assertEquals("kept", content.textContent())
    }

    // ──────────────────────────────────────────────────────────────────────
    // 9. tool_use / tool_result round-trip through Blocks with discriminators
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `tool_use and tool_result blocks round-trip with correct SerialName discriminators`() {
        val original = Message(
            role = "assistant",
            content = MessageContent.Blocks(
                listOf(
                    ContentBlock.ToolUse(
                        id = "tu_1",
                        name = "get_weather",
                        input = buildJsonObject { put("city", "Paris") }
                    ),
                    ContentBlock.ToolResult(
                        toolUseId = "tu_1",
                        content = "sunny",
                        isError = false
                    )
                )
            )
        )

        val encoded = json.encodeToString(original)
        assertTrue("tool_use discriminator present", encoded.contains("\"type\":\"tool_use\""))
        assertTrue("tool_result discriminator present", encoded.contains("\"type\":\"tool_result\""))
        assertTrue("tool_use_id field present", encoded.contains("\"tool_use_id\":\"tu_1\""))

        val decoded = json.decodeFromString<Message>(encoded)
        assertEquals(original, decoded)

        val blocks = (decoded.content as MessageContent.Blocks).blocks
        val toolUse = blocks.filterIsInstance<ContentBlock.ToolUse>().single()
        assertEquals("tu_1", toolUse.id)
        assertEquals("get_weather", toolUse.name)
        val toolResult = blocks.filterIsInstance<ContentBlock.ToolResult>().single()
        assertEquals("tu_1", toolResult.toolUseId)
        assertEquals("sunny", toolResult.content)
        assertFalse(toolResult.isError)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 10. Blocks with empty list serializes to [] and round-trips
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `Blocks with empty list serializes to an empty array and round-trips`() {
        val original = Message(role = "user", content = MessageContent.Blocks(emptyList()))

        val encoded = json.encodeToString(original)
        val contentElement = json.parseToJsonElement(encoded).jsonObjectContent()
        assertTrue("content should be a JsonArray", contentElement is JsonArray)
        assertEquals(0, (contentElement as JsonArray).size)

        val decoded = json.decodeFromString<Message>(encoded)
        assertEquals(original, decoded)
        assertTrue(decoded.content is MessageContent.Blocks)
        assertTrue((decoded.content as MessageContent.Blocks).blocks.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Parse an encoded [Message] JSON and return its `content` element. */
    private fun kotlinx.serialization.json.JsonElement.jsonObjectContent() =
        (this as JsonObject).getValue("content")
}
