package com.starfarer.companionoverlay

import android.app.Application
import com.starfarer.companionoverlay.di.appModule
import com.starfarer.companionoverlay.di.audioModule
import com.starfarer.companionoverlay.di.overlayModule
import com.starfarer.companionoverlay.di.storageModule
import com.starfarer.companionoverlay.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
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
        
        DebugLog.log("App", "CompanionApplication initialized")
    }
}
