package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.ClaudeApi
import com.starfarer.companionoverlay.ClaudeAuth
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.SettingsRepository
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * Core application dependencies — things that live for the entire app lifecycle.
 */
val appModule = module {
    
    // Shared OkHttpClient with sensible timeouts
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    
    // Event coordinator — replaces static instance pattern
    single { OverlayCoordinator() }
    
    // Settings repository — wraps SharedPreferences with proper abstraction
    // Uses both plain prefs (settings) and encrypted prefs (auth) for security
    single {
        SettingsRepository(
            settingsPrefs = get(named("settings")),
            securePrefs = get(named("auth")),
            presetProvider = { CharacterPreset.getActive(androidContext()) }
        )
    }
    
    // Auth lives for the app lifetime — single instance
    single { ClaudeAuth(androidContext()) }
    
    // API client depends on auth and settings
    single { ClaudeApi(get(), get()) }
}
