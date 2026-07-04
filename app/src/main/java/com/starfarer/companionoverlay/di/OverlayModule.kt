package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.BeepManager
import com.starfarer.companionoverlay.CameraManager
import com.starfarer.companionoverlay.ScreenshotManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Overlay service dependencies.
 *
 * Conversation state lives server-side in Nexus now — there is no local
 * history or storage to manage. What's left here are the device-capability
 * helpers the overlay (and the gateway's cap_request handling) rely on.
 *
 * [ScreenshotManager] is a factory — stateless. [BeepManager] is a singleton — persistent SoundPool.
 */
val overlayModule = module {

    // ScreenshotManager — stateless, takes only context and coordinator
    factory { ScreenshotManager(androidContext(), get()) }

    // CameraManager — stateless headless capture; spins up its own short-lived camera binding
    factory { CameraManager(androidContext()) }

    // BeepManager — persistent SoundPool for consistent Bluetooth audio
    single { BeepManager(androidContext()) }
}
