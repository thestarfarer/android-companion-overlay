package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.AudioCoordinator
import com.starfarer.companionoverlay.BeepManager
import com.starfarer.companionoverlay.TtsManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Audio-related dependencies: on-device TTS and coordination.
 *
 * All audio components are singletons to maintain consistent state
 * (speaking status, keep-alive, etc) across the app. Remote (Gemini) voice
 * synthesis is gone — server-side `speak.audio` is the future remote path,
 * on-device TTS the local one.
 */
val audioModule = module {

    single { TtsManager(androidContext(), get()) }

    single { AudioCoordinator(androidContext(), get(), get(), get<BeepManager>()) }
}
