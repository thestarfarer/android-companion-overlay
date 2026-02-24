package com.starfarer.companionoverlay

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings host activity.
 * Uses PreferenceFragmentCompat for settings content.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HIGHLIGHT_KEY = "highlight_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Back button - ImageView now instead of TextView
        findViewById<View>(R.id.backButton).setOnClickListener {
            finishAfterTransition()
        }

        onBackPressedDispatcher.addCallback(this) {
            finishAfterTransition()
        }

        if (savedInstanceState == null) {
            val fragment = SettingsFragment().apply {
                arguments = Bundle().apply {
                    intent.getStringExtra(EXTRA_HIGHLIGHT_KEY)?.let {
                        putString(EXTRA_HIGHLIGHT_KEY, it)
                    }
                }
            }
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, fragment)
                .commit()
        }
    }

}
