package com.starfarer.companionoverlay

/**
 * Markdown/formatting cleanup for text that is about to be spoken aloud.
 *
 * One shared implementation — TtsManager and GeminiTtsManager used to carry
 * private copies that had already diverged (only one stripped `---` rules,
 * neither handled links).
 */
object TtsTextCleaner {

    fun clean(text: String): String = text
        // [label](url) → label, before the bare-URL pass eats the url part
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        // Bare URLs read terribly — replace with a spoken placeholder
        .replace(Regex("""https?://\S+"""), "link")
        // Code blocks before inline code before emphasis markers
        .replace(Regex("```[\\s\\S]*?```"), "")
        .replace(Regex("`[^`]*`"), "")
        .replace(Regex("\\*+"), "")
        .replace(Regex("~+"), "")
        .replace(Regex("#+\\s*"), "")
        .replace(Regex("---+"), "")
        .trim()
}
