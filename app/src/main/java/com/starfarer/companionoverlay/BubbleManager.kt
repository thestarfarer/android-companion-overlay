package com.starfarer.companionoverlay

import android.content.Context
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/**
 * Manages the speech bubbles — brief notifications, full response dialogs with reply
 * input, and voice status indicators.
 *
 * Owns the bubble views and their lifecycle. Where each bubble is placed and removed is
 * delegated to a [BubbleSurface] (real overlay window vs. in-app view group), so this class
 * never touches `WindowManager` directly. Calls back to the service through [Host] for
 * actions it can't handle itself (TTS, ghost mode, sending replies, voice toggle).
 */
class BubbleManager(
    private val context: Context,
    private val surface: BubbleSurface,
    private val handler: Handler,
    private val host: Host
) {

    interface Host {
        fun onTtsStop()
        fun onSendReply(text: String)
        fun onVoiceToggle()
        fun onKeyboardShown()

        /**
         * The reply keyboard closed (IME dismissed or bubble torn down). This is
         * the ghost-mode exit that does NOT depend on the optional accessibility
         * service — without it, typing a reply left the sprite permanently
         * ghosted for users who never enabled that service.
         */
        fun onKeyboardHidden()
        val screenHeight: Int
    }

    // --- Speech bubble (main response dialog + brief notifications) ---
    private var speechBubble: View? = null
    private var speechBubbleIsToast = false
    var pendingDismiss: Runnable? = null
        private set

    // --- Voice bubble (recording indicator) ---
    private var voiceBubble: TextView? = null

    // True between the reply input raising the keyboard and its dismissal —
    // gates onKeyboardShown/Hidden so they fire exactly once per session.
    private var replyKeyboardActive = false

    // --- Brief bubble ---

    fun showBrief(message: String, durationMs: Long = 3000L) {
        cancelPendingDismiss()
        hideSpeechBubble()

        val d = context.resources.displayMetrics.density
        val colors = BubbleStyle.colors(context)

        val bubble = TextView(context).apply {
            text = message
            setTextColor(colors.text)
            textSize = 13f
            background = BubbleStyle.toastBackground(colors, 20f, d)
            val pad = (10 * d).toInt()
            setPadding(pad + 8, pad, pad + 8, pad)
            gravity = Gravity.START
        }

        try {
            bubble.alpha = 0f
            // Step down when the voice indicator holds the primary toast slot —
            // both anchored top-right, they used to fully overlap.
            val placement = if (voiceBubble != null) BubblePlacement.TOP_RIGHT_TOAST_STACKED
                else BubblePlacement.TOP_RIGHT_TOAST
            surface.attach(bubble, placement)
            bubble.animate().alpha(1f).setDuration(200).start()
            speechBubble = bubble
            speechBubbleIsToast = true

            bubble.setOnClickListener { host.onTtsStop(); hideSpeechBubble() }
            scheduleDismiss(durationMs)
        } catch (e: Exception) {
            DebugLog.log("Bubble", "Failed to show brief bubble: ${e.message}")
        }
    }

    // --- Full response dialog ---

    fun showResponse(message: String, timeoutMs: Long) {
        DebugLog.log("Bubble", "Showing speech bubble: ${message.take(60)}")
        cancelPendingDismiss()
        hideSpeechBubble()

        val d = context.resources.displayMetrics.density
        val colors = BubbleStyle.colors(context)
        val bgDrawable = BubbleStyle.background(colors, 24f, d)

        val responseText = TextView(context).apply {
            text = message
            setTextColor(colors.text)
            textSize = 15f
            val pad = (16 * d).toInt()
            setPadding(pad + 8, 0, pad + 8, (8 * d).toInt())
            gravity = Gravity.CENTER
            setLineSpacing(4f, 1f)
        }

        val inputBg = android.graphics.drawable.GradientDrawable().apply {
            setColor((colors.text and 0x00FFFFFF) or 0x18000000.toInt())
            cornerRadius = 16f * d
        }

        val replyInput = android.widget.EditText(context).apply {
            hint = context.getString(R.string.bubble_reply_hint)
            setHintTextColor((colors.text and 0x00FFFFFF) or 0x66000000.toInt())
            setTextColor(colors.text)
            textSize = 13f
            background = inputBg
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            val inputPad = (10 * d).toInt()
            setPadding(inputPad + 4, inputPad, inputPad, inputPad)
        }

        val sendButton = TextView(context).apply {
            text = "\u27A4"
            textSize = 20f
            setTextColor(colors.text)
            gravity = Gravity.CENTER
            val btnPad = (8 * d).toInt()
            setPadding(btnPad + 4, btnPad, btnPad + 4, btnPad)
        }

        val inputRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val rowPad = (8 * d).toInt()
            setPadding(rowPad + 4, 0, rowPad + 4, rowPad)
            addView(replyInput, android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(sendButton, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val cornerPad = (24 * d).toInt()
        val responseScroll = android.widget.ScrollView(context).apply {
            addView(responseText)
            isVerticalScrollBarEnabled = true
            setPadding(0, cornerPad, 0, cornerPad)
            clipToPadding = false
            BubbleStyle.scrollbarThumb(context, d)?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    setVerticalScrollbarThumbDrawable(it)
                }
            }
        }

        val maxW = (context.resources.displayMetrics.widthPixels * 0.85).toInt()
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = bgDrawable
            minimumWidth = (200 * d).toInt()
            addView(responseScroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(inputRow, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        try {
            container.alpha = 0f
            surface.attach(container, BubblePlacement.CENTERED_DIALOG, maxW)
            container.animate().alpha(1f).setDuration(300).start()
            speechBubble = container
            speechBubbleIsToast = false

            val maxResponseH = (host.screenHeight * 0.6).toInt()
            responseScroll.post {
                if (responseScroll.height > maxResponseH) {
                    responseScroll.layoutParams.height = maxResponseH
                    responseScroll.requestLayout()
                }
            }

            responseText.setOnClickListener { host.onTtsStop(); hideSpeechBubble() }

            replyInput.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    // Promotion happens at most once per bubble; the other side
                    // effects must run on every tap — gating them all on the
                    // promotion result used to skip dismiss-cancel and ghost
                    // handling from the second tap onward.
                    if (surface.makeFocusable(container)) {
                        replyInput.requestFocus()
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        replyInput.post { imm.showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT) }
                    }
                    cancelPendingDismiss()
                    if (!replyKeyboardActive) {
                        replyKeyboardActive = true
                        host.onKeyboardShown()
                    }
                }
                false
            }

            // Detect the reply keyboard closing via IME insets on our own window —
            // the focused window receives them, so this works with no accessibility
            // service. Only react after the IME was actually seen open (the first
            // insets pass arrives before it shows).
            var imeWasVisible = false
            container.setOnApplyWindowInsetsListener { _, insets ->
                val imeVisible = insets.isVisible(android.view.WindowInsets.Type.ime())
                if (imeVisible) {
                    imeWasVisible = true
                } else if (imeWasVisible) {
                    imeWasVisible = false
                    onReplyKeyboardDismissed(container)
                }
                insets
            }

            replyInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val text = replyInput.text.toString().trim()
                    if (text.isNotEmpty()) host.onSendReply(text)
                    true
                } else false
            }

            sendButton.setOnClickListener {
                val text = replyInput.text.toString().trim()
                if (text.isNotEmpty()) host.onSendReply(text)
            }

            scheduleDismiss(timeoutMs)
        } catch (e: Exception) {
            DebugLog.log("Bubble", "Failed to show speech bubble: ${e.message}")
        }
    }

    // --- Voice bubble ---

    fun showVoice(text: String) {
        hideVoice()
        val d = context.resources.displayMetrics.density
        val colors = BubbleStyle.colors(context)

        val bubble = TextView(context).apply {
            this.text = text
            setTextColor(colors.text)
            textSize = 13f
            background = BubbleStyle.toastBackground(colors, 20f, d)
            val pad = (10 * d).toInt()
            setPadding(pad + 8, pad, pad + 8, pad)
            gravity = Gravity.START
        }

        try {
            bubble.alpha = 0f
            // Mirror of showBrief: step down if a brief toast already holds the slot.
            val placement = if (speechBubble != null && speechBubbleIsToast)
                BubblePlacement.TOP_RIGHT_TOAST_STACKED else BubblePlacement.TOP_RIGHT_TOAST
            surface.attach(bubble, placement)
            bubble.animate().alpha(1f).setDuration(200).start()
            voiceBubble = bubble
            bubble.setOnClickListener { host.onVoiceToggle() }
        } catch (e: Exception) {
            DebugLog.log("Bubble", "Failed to show voice bubble: ${e.message}")
        }
    }

    fun updateVoice(text: String) {
        voiceBubble?.text = text
    }

    fun hideVoice() {
        voiceBubble?.let { bubble ->
            bubble.animate().alpha(0f).setDuration(150).withEndAction {
                surface.detach(bubble)
            }.start()
        }
        voiceBubble = null
    }

    // --- Speech bubble lifecycle ---

    fun hideSpeechBubble(animate: Boolean = true) {
        val bubble = speechBubble ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        bubble.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        // Fallback exit: if the insets path never fired, the bubble going away
        // must still clear ghost mode (no-op when the keyboard wasn't up).
        onReplyKeyboardDismissed(bubble)

        if (animate) {
            bubble.animate().alpha(0f).setDuration(250).withEndAction {
                surface.detach(bubble)
            }.start()
        } else {
            surface.detach(bubble)
        }
        speechBubble = null
    }

    /**
     * The reply keyboard closed (IME insets or bubble teardown). Demote the
     * window so it stops swallowing Back/key events meant for the app underneath,
     * and let the host clear ghost mode. Gated to fire once per keyboard session.
     */
    private fun onReplyKeyboardDismissed(bubble: View) {
        if (!replyKeyboardActive) return
        replyKeyboardActive = false
        surface.makeUnfocusable(bubble)
        host.onKeyboardHidden()
    }

    fun cancelPendingDismiss() {
        pendingDismiss?.let { handler.removeCallbacks(it) }
        pendingDismiss = null
    }

    private fun scheduleDismiss(delayMs: Long) {
        val dismiss = Runnable { hideSpeechBubble() }
        pendingDismiss = dismiss
        handler.postDelayed(dismiss, delayMs)
    }

    /**
     * Immediate teardown for service destroy. The animated hide paths remove
     * windows inside withEndAction, which a dying service can cancel — leaking
     * the bubble windows until the process exits. Detach synchronously instead.
     */
    fun destroy() {
        cancelPendingDismiss()
        voiceBubble?.let { surface.detach(it) }
        voiceBubble = null
        hideSpeechBubble(animate = false)
    }
}
