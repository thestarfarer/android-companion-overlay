package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.AudioCoordinator
import com.starfarer.companionoverlay.GeminiTtsManager
import com.starfarer.companionoverlay.TtsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Audio-related dependencies — TTS engines, coordination.
 *
 * All audio components are singletons to maintain consistent state
 * (speaking status, fallback chains, etc) across the app.
 */
val audioModule = module {
    
    single { TtsManager(androidContext()) }
    
    single { GeminiTtsManager(androidContext()) }
    
    // AudioCoordinator depends on both TTS managers and settings
    single { AudioCoordinator(androidContext(), get(), get(), get()) }
}
