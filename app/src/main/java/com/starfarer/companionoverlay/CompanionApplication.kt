package com.starfarer.companionoverlay

import android.app.Application
import com.starfarer.companionoverlay.di.appModule
import com.starfarer.companionoverlay.di.audioModule
import com.starfarer.companionoverlay.di.overlayModule
import com.starfarer.companionoverlay.di.storageModule
import com.starfarer.companionoverlay.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import android.app.NotificationChannel
import android.app.NotificationManager
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application entry point. Initializes Koin for dependency injection.
 */
class CompanionApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@CompanionApplication)
            modules(
                appModule,
                audioModule,
                storageModule,
                overlayModule,
                viewModelModule
            )
        }
        
        createNotificationChannel()

        DebugLog.log("App", "CompanionApplication initialized")
    }

    /**
     * Notification channels, created once in Application so neither the
     * activity nor the service need to duplicate this setup.
     *
     * Two channels because they need opposite importance: the foreground
     * service notification must stay quiet (IMPORTANCE_LOW), while a
     * server-requested `notify` capability is Senni deliberately reaching
     * for the user's attention — posting those on the LOW channel made them
     * silent and invisible, i.e. "notify does nothing".
     */
    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.svc_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.svc_channel_description)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.alert_channel_description)
            }
        )
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "companion_overlay_channel"

        /** Server-requested `notify` capability — heads-up, with sound. */
        const val ALERT_CHANNEL_ID = "companion_alerts_channel"
    }
}
