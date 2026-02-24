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
     * Notification channel for the overlay foreground service.
     * Created once in Application so neither the activity nor the service
     * need to duplicate this setup.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Companion Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Senni alive on your screen"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "companion_overlay_channel"
    }
}
