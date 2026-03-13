package com.starfarer.companionoverlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/**
 * Manages the overlay speech bubbles — brief notifications, full response
 * dialogs with reply input, and voice status indicators.
 *
 * Owns the bubble views and their lifecycle. Calls back to the service
 * through [Host] for actions it can't handle itself (TTS, ghost mode,
 * sending replies, voice toggle).
 */
class BubbleManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val handler: Handler,
    private val host: Host
) {

    interface Host {
        fun onTtsStop()
        fun onSendReply(text: String)
        fun onVoiceToggle()
        fun onKeyboardShown()
        fun cancelBubbleTimeout()
        val screenHeight: Int
    }

    // --- Speech bubble (main response dialog + brief notifications) ---
    private var speechBubble: View? = null
    private var speechParams: WindowManager.LayoutParams? = null
    var pendingDismiss: Runnable? = null
        private set

    // --- Voice bubble (recording indicator) ---
    private var voiceBubble: TextView? = null
    private var voiceBubbleParams: WindowManager.LayoutParams? = null

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

        val bubbleParams = BubbleStyle.topRightEdgeParams(d)

        try {
            bubble.alpha = 0f
            windowManager.addView(bubble, bubbleParams)
            bubble.animate().alpha(1f).setDuration(200).start()
            speechBubble = bubble
            speechParams = bubbleParams

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
            hint = "Reply..."
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

        val bubbleParams = BubbleStyle.centeredParams(maxW)

        try {
            container.alpha = 0f
            windowManager.addView(container, bubbleParams)
            container.animate().alpha(1f).setDuration(300).start()
            speechBubble = container
            speechParams = bubbleParams

            val maxResponseH = (host.screenHeight * 0.6).toInt()
            responseScroll.post {
                if (responseScroll.height > maxResponseH) {
                    responseScroll.layoutParams.height = maxResponseH
                    responseScroll.requestLayout()
                }
            }

            responseText.setOnClickListener { host.onTtsStop(); hideSpeechBubble() }

            replyInput.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN &&
                    bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0
                ) {
                    bubbleParams.flags = bubbleParams.flags and
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    try { windowManager.updateViewLayout(container, bubbleParams) } catch (_: Exception) {}
                    replyInput.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    replyInput.post { imm.showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT) }
                    cancelPendingDismiss()
                    host.onKeyboardShown()
                }
                false
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

        val params = BubbleStyle.topRightEdgeParams(d)

        try {
            bubble.alpha = 0f
            windowManager.addView(bubble, params)
            bubble.animate().alpha(1f).setDuration(200).start()
            voiceBubble = bubble
            voiceBubbleParams = params
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
                try { windowManager.removeView(bubble) } catch (_: Exception) {}
            }.start()
        }
        voiceBubble = null
        voiceBubbleParams = null
    }

    // --- Speech bubble lifecycle ---

    fun hideSpeechBubble(animate: Boolean = true) {
        val bubble = speechBubble ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        bubble.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }

        if (animate) {
            bubble.animate().alpha(0f).setDuration(250).withEndAction {
                try { windowManager.removeView(bubble) } catch (_: Exception) {}
            }.start()
        } else {
            try { windowManager.removeView(bubble) } catch (_: Exception) {}
        }
        speechBubble = null
        speechParams = null
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
}
