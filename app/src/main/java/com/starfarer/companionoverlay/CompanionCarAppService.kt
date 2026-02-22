package com.starfarer.companionoverlay

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto. Discovered by the host via the manifest intent-filter.
 */
class CompanionCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Allow all hosts in debug builds; restrict to known hosts in release
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return CompanionCarSession()
    }
}
