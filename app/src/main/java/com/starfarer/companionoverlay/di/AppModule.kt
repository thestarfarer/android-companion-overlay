package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.ClaudeApi
import com.starfarer.companionoverlay.ClaudeAuth
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.PresetRepository
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
    
    // Shared OkHttpClient with sensible timeouts.
    // ClaudeApi and ClaudeAuth derive child clients via newBuilder() for their
    // own timeout needs, sharing this instance's connection pool and dispatcher.
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
    single {
        SettingsRepository(
            settingsPrefs = get(named("settings")),
            securePrefs = get(named("auth")),
            presetProvider = { get<PresetRepository>().getActive() }
        )
    }
    
    // Auth — shares the app's HTTP client and encrypted prefs
    single { ClaudeAuth(androidContext(), get(), get(named("auth"))) }
    
    // API client depends on shared HTTP client, auth, and settings
    single { ClaudeApi(get(), get(), get()) }
}
