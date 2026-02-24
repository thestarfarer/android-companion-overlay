package com.starfarer.companionoverlay.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Data models for Claude API requests and responses.
 *
 * These replace manual JSONObject construction with type-safe,
 * serializable classes that kotlinx.serialization handles.
 */

// ══════════════════════════════════════════════════════════════════════════
// Request Models
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

@Serializable
data class SystemBlock(
    val type: String = "text",
    val text: String
)

@Serializable
data class RequestMetadata(
    @SerialName("user_id") val userId: String
)

@Serializable
data class Tool(
    val type: String,
    val name: String,
    @SerialName("max_uses") val maxUses: Int? = null
)

// ══════════════════════════════════════════════════════════════════════════
// Message Models (support both text and multimodal)
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class Message(
    val role: String,
    val content: MessageContent
)

@Serializable
sealed class MessageContent {
    @Serializable
    @SerialName("text")
    data class Text(val value: String) : MessageContent()
    
    @Serializable
    @SerialName("blocks")
    data class Blocks(val blocks: List<ContentBlock>) : MessageContent()
}

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class TextBlock(
        val type: String = "text",
        val text: String
    ) : ContentBlock()
    
    @Serializable
    @SerialName("image")
    data class ImageBlock(
        val type: String = "image",
        val source: ImageSource
    ) : ContentBlock()
}

@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String = "image/jpeg",
    val data: String
)

// ══════════════════════════════════════════════════════════════════════════
// Response Models
// ══════════════════════════════════════════════════════════════════════════

@Serializable
data class ClaudeResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ResponseContent> = emptyList(),
    val model: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    val usage: Usage? = null,
    val error: ErrorResponse? = null
)

@Serializable
data class ResponseContent(
    val type: String,
    val text: String? = null
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

@Serializable
data class ErrorResponse(
    val type: String? = null,
    val message: String? = null
)

// ══════════════════════════════════════════════════════════════════════════
// Serialization Helpers
// ══════════════════════════════════════════════════════════════════════════

/**
 * Custom serializer to handle Claude's flexible message content format.
 * Messages can be either a plain string or an array of content blocks.
 */
object MessageContentSerializer {
    
    /**
     * Convert a plain text message to API format.
     */
    fun textMessage(role: String, text: String): Map<String, Any> = mapOf(
        "role" to role,
        "content" to text
    )
    
    /**
     * Convert a multimodal message (with image) to API format.
     */
    fun multimodalMessage(
        role: String,
        imageBase64: String,
        text: String
    ): Map<String, Any> = mapOf(
        "role" to role,
        "content" to listOf(
            mapOf(
                "type" to "image",
                "source" to mapOf(
                    "type" to "base64",
                    "media_type" to "image/jpeg",
                    "data" to imageBase64
                )
            ),
            mapOf(
                "type" to "text",
                "text" to text
            )
        )
    )
}
