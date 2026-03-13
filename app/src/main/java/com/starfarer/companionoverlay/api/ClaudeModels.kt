package com.starfarer.companionoverlay.api

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

/**
 * Type-safe models for the Claude Messages API.
 *
 * Used by [ClaudeApi] for request building and response parsing, and by
 * [ConversationManager] / [ConversationStorage] for in-memory and on-disk
 * conversation history.
 *
 * The main design challenge is that Claude's `content` field is polymorphic:
 * it's either a plain string or an array of content blocks. The custom
 * [MessageContentSerializer] handles this transparently — text-only messages
 * serialize to a bare string, multimodal messages serialize to a block array,
 * and deserialization handles both forms.
 */

// ══════════════════════════════════════════════════════════════════════════
// Request
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val stream: Boolean = false,
    val system: List<SystemBlock>,
    val messages: List<Message>,
    val metadata: RequestMetadata? = null,
    val tools: List<Tool>? = null
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SystemBlock(
    @EncodeDefault val type: String = "text",
    val text: String
)

@Serializable
data class RequestMetadata(
    @SerialName("user_id") val userId: String
)

@Serializable
data class Tool(
    val type: String? = null,
    val name: String,
    @SerialName("max_uses") val maxUses: Int? = null,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject? = null
)

// ══════════════════════════════════════════════════════════════════════════
// Messages
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class Message(
    val role: String,
    val content: @Serializable(with = MessageContentSerializer::class) MessageContent,
    val timestamp: Long = 0L
)

/**
 * Message content — either plain text or a list of content blocks.
 *
 * Serializes as:
 * - [Text]: bare JSON string (`"Hello"`)
 * - [Blocks]: JSON array of content blocks (`[{"type":"text","text":"Hello"}]`)
 *
 * This matches Claude's API format exactly.
 */
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Blocks(val blocks: List<ContentBlock>) : MessageContent()

    /** Convenience: extract plain text regardless of variant. */
    fun textContent(): String = when (this) {
        is Text -> text
        is Blocks -> blocks.filterIsInstance<ContentBlock.Text>()
            .joinToString("") { it.text }
    }
}

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(val source: ImageSource) : ContentBlock()

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        @EncodeDefault val input: JsonObject = JsonObject(emptyMap())
    ) : ContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false
    ) : ContentBlock()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImageSource(
    @EncodeDefault val type: String = "base64",
    @EncodeDefault @SerialName("media_type") val mediaType: String = "image/jpeg",
    val data: String
)

// ══════════════════════════════════════════════════════════════════════════
// Response
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class ClaudeResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ResponseBlock> = emptyList(),
    val model: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: Usage? = null,
    val error: ErrorBody? = null
) {
    /** Extract concatenated text from all text blocks in the response. */
    fun text(): String = content
        .filter { it.type == "text" }
        .mapNotNull { it.text }
        .joinToString("")

    /** Check if the response requires tool execution. */
    fun hasToolUse(): Boolean = stopReason == "tool_use"

    /** Extract tool_use blocks from the response. */
    fun toolUseBlocks(): List<ResponseBlock> = content.filter { it.type == "tool_use" }
}

@Serializable
data class ResponseBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

@Serializable
data class ErrorBody(
    val type: String? = null,
    val message: String? = null
)

// ══════════════════════════════════════════════════════════════════════════
// Custom Serializer
// ══════════════════════════════════════════════════════════════════════════

/**
 * Handles Claude's polymorphic content field:
 * - String → [MessageContent.Text]
 * - Array  → [MessageContent.Blocks]
 *
 * This is the piece that the previous model design was missing —
 * kotlinx.serialization's default sealed class handling adds type
 * discriminators that Claude's API doesn't understand.
 */
object MessageContentSerializer : KSerializer<MessageContent> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("MessageContent")

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is MessageContent.Text -> jsonEncoder.encodeJsonElement(
                JsonPrimitive(value.text)
            )
            is MessageContent.Blocks -> jsonEncoder.encodeJsonElement(
                jsonEncoder.json.encodeToJsonElement(
                    ListSerializer(ContentBlock.serializer()),
                    value.blocks
                )
            )
        }
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        val jsonDecoder = decoder as JsonDecoder
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> MessageContent.Text(element.content)
            is JsonArray -> MessageContent.Blocks(
                jsonDecoder.json.decodeFromJsonElement(
                    ListSerializer(ContentBlock.serializer()),
                    element
                )
            )
            else -> MessageContent.Text("")
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Builder helpers
// ══════════════════════════════════════════════════════════════════════════

/** Build a text-only user message. */
fun textMessage(text: String): Message = Message(
    role = "user",
    content = MessageContent.Text(text),
    timestamp = System.currentTimeMillis()
)

/** Build a user message with an image and optional text. */
fun screenshotMessage(imageBase64: String, text: String): Message = Message(
    role = "user",
    content = MessageContent.Blocks(listOf(
        ContentBlock.Image(ImageSource(data = imageBase64)),
        ContentBlock.Text(text)
    )),
    timestamp = System.currentTimeMillis()
)

/** Build an assistant message from response text. */
fun assistantMessage(text: String): Message = Message(
    role = "assistant",
    content = MessageContent.Text(text),
    timestamp = System.currentTimeMillis()
)
