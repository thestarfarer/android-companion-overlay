package com.starfarer.companionoverlay

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings host activity.
 * Uses PreferenceFragmentCompat for settings content.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Back button - ImageView now instead of TextView
        findViewById<View>(R.id.backButton).setOnClickListener {
            finishAfterTransition()
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onBackPressed() {
        finishAfterTransition()
    }
}
