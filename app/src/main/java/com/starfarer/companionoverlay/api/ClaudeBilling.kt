package com.starfarer.companionoverlay.api

import java.security.MessageDigest

/**
 * Computes billing headers for Claude API requests.
 *
 * This is the fingerprinting mechanism used by Claude CLI to identify
 * legitimate client requests. Extracted from ClaudeApi for clarity.
 */
object ClaudeBilling {
    
    private const val BILLING_SALT = "59cf53e54c78"
    const val CLIENT_VERSION = "2.1.72"
    
    /**
     * Compute the billing header based on the first user message content.
     *
     * The algorithm samples characters at positions 4, 7, and 20 of the
     * first user message text, combines them with a salt and version,
     * then takes the first 3 characters of the SHA-256 hash.
     *
     * @param firstUserMessageText The text content of the first user message
     * @return The complete billing header string
     */
    fun computeHeader(firstUserMessageText: String): String {
        val text = firstUserMessageText
        val c4 = if (text.length > 4) text[4] else '0'
        val c7 = if (text.length > 7) text[7] else '0'
        val c20 = if (text.length > 20) text[20] else '0'
        
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("$BILLING_SALT$c4$c7$c20$CLIENT_VERSION".toByteArray())
            .joinToString("") { "%02x".format(it) }
        
        return "x-anthropic-billing-header: cc_version=$CLIENT_VERSION.${hash.take(3)}; cc_entrypoint=cli; cch=00000;"
    }
}
