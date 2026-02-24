package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.BeepManager
import com.starfarer.companionoverlay.ConversationManager
import com.starfarer.companionoverlay.ScreenshotManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Overlay service dependencies.
 *
 * ConversationManager is scoped as a singleton because it holds conversation
 * history and coroutine scope. The overlay service manages its lifecycle by
 * calling destroy() in onDestroy.
 *
 * ScreenshotManager and BeepManager are factories — stateless or lightweight.
 */
val overlayModule = module {
    
    // ConversationManager — singleton scoped to overlay service lifecycle
    // The service is responsible for calling destroy() when it stops
    single { ConversationManager(androidContext(), get(), get()) }
    
    // ScreenshotManager — stateless, takes only context and coordinator
    factory { ScreenshotManager(androidContext(), get()) }
    
    // BeepManager — lightweight audio feedback, factory scoped
    factory { BeepManager() }
}
