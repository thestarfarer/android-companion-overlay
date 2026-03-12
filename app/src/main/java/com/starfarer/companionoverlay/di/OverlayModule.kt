package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.BeepManager
import com.starfarer.companionoverlay.ConversationManager
import com.starfarer.companionoverlay.ConversationStorage
import com.starfarer.companionoverlay.ScreenshotManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Overlay service dependencies.
 *
 * [ConversationManager] is a factory — each overlay service instance gets
 * a fresh manager with a new coroutine scope. The service is responsible
 * for calling [ConversationManager.destroy] in onDestroy, which cancels
 * the scope permanently. On the next service start, Android creates a new
 * service instance, whose `by inject()` delegate resolves a new factory
 * instance. No scope resurrection needed.
 *
 * [ConversationStorage] remains a singleton — it's stateless (just wraps
 * a File path) and safe to share across service restarts.
 *
 * [ScreenshotManager] and [BeepManager] are factories — stateless/lightweight.
 */
val overlayModule = module {

    // File-based conversation persistence
    single { ConversationStorage(androidContext()) }

    // ConversationManager — factory scoped to each service lifecycle
    factory { ConversationManager(androidContext(), get(), get(), get(), get()) }

    // ScreenshotManager — stateless, takes only context and coordinator
    factory { ScreenshotManager(androidContext(), get()) }

    // BeepManager — lightweight audio feedback, factory scoped
    factory { BeepManager() }
}
