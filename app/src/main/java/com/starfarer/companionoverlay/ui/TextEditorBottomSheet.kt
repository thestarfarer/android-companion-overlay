package com.starfarer.companionoverlay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.starfarer.companionoverlay.R

/**
 * Bottom sheet for editing text content (system prompts, user messages).
 *
 * BottomSheet naturally handles:
 * - Sizing to content
 * - Expanding up to screen limits
 * - Scroll physics via NestedScrollView in layout
 * - Consistent positioning (anchored to bottom)
 */
class TextEditorBottomSheet : BottomSheetDialogFragment() {

    private var title: String = ""
    private var currentText: String = ""
    private var defaultText: String = ""
    private var onSave: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            title = it.getString(ARG_TITLE, "")
            currentText = it.getString(ARG_CURRENT_TEXT, "")
            defaultText = it.getString(ARG_DEFAULT_TEXT, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_text_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleView = view.findViewById<TextView>(R.id.dialogTitle)
        val editText = view.findViewById<EditText>(R.id.editText)
        val resetButton = view.findViewById<Button>(R.id.resetButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        val saveButton = view.findViewById<Button>(R.id.saveButton)

        titleView.text = title
        editText.setText(currentText)
        editText.setSelection(editText.text.length)

        resetButton.setOnClickListener {
            editText.setText(defaultText)
            editText.setSelection(editText.text.length)
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        saveButton.setOnClickListener {
            val newText = editText.text.toString().trim()
            if (newText.isEmpty()) {
                Toast.makeText(requireContext(), "Prompt can't be empty~", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSave?.invoke(newText)
            Toast.makeText(requireContext(), "Saved~", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        // Allow expansion up to 85% of screen
        dialog?.let { dialog ->
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = resources.displayMetrics
                val maxHeight = (displayMetrics.heightPixels * 0.85).toInt()
                behavior.maxHeight = maxHeight
                behavior.peekHeight = BottomSheetBehavior.PEEK_HEIGHT_AUTO
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    fun setOnSaveListener(listener: (String) -> Unit) {
        onSave = listener
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_CURRENT_TEXT = "currentText"
        private const val ARG_DEFAULT_TEXT = "defaultText"

        fun newInstance(
            title: String,
            currentText: String,
            defaultText: String
        ): TextEditorBottomSheet {
            return TextEditorBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_CURRENT_TEXT, currentText)
                    putString(ARG_DEFAULT_TEXT, defaultText)
                }
            }
        }
    }
}
