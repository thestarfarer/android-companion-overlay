package com.starfarer.companionoverlay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.starfarer.companionoverlay.R

/**
 * Bottom sheet for editing text content (system prompts, user messages).
 *
 * Results are delivered via the Fragment Result API ([setFragmentResult]) to a
 * caller-supplied [ARG_REQUEST_KEY]. Each editable field uses its OWN key, and
 * callers register the listener once at Activity-create time — registering at
 * show-time with the Activity as owner meant a rotation dropped the listener
 * (the save was lost) and the buffered result could later land in the wrong
 * field, since both fields shared one key.
 */
class TextEditorBottomSheet : BottomSheetDialogFragment() {

    private var title: String = ""
    private var currentText: String = ""
    private var defaultText: String = ""
    private var requestKey: String = LEGACY_REQUEST_KEY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            title = it.getString(ARG_TITLE, "")
            currentText = it.getString(ARG_CURRENT_TEXT, "")
            defaultText = it.getString(ARG_DEFAULT_TEXT, "")
            requestKey = it.getString(ARG_REQUEST_KEY, LEGACY_REQUEST_KEY)
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
            parentFragmentManager.setFragmentResult(
                requestKey,
                bundleOf(RESULT_TEXT to newText)
            )
            Toast.makeText(requireContext(), "Saved~", Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
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

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_CURRENT_TEXT = "currentText"
        private const val ARG_DEFAULT_TEXT = "defaultText"
        private const val ARG_REQUEST_KEY = "requestKey"

        private const val LEGACY_REQUEST_KEY = "text_editor_result"
        const val RESULT_TEXT = "result_text"

        fun newInstance(
            title: String,
            currentText: String,
            defaultText: String,
            requestKey: String
        ): TextEditorBottomSheet {
            return TextEditorBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_CURRENT_TEXT, currentText)
                    putString(ARG_DEFAULT_TEXT, defaultText)
                    putString(ARG_REQUEST_KEY, requestKey)
                }
            }
        }
    }
}
