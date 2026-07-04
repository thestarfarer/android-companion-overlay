package com.starfarer.companionoverlay.di

import android.os.Handler
import android.os.Looper
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.gateway.AvatarRepository
import com.starfarer.companionoverlay.gateway.GatewayClient
import com.starfarer.companionoverlay.gateway.GatewayConfig
import com.starfarer.companionoverlay.repository.PresetRepository
import com.starfarer.companionoverlay.repository.SettingsRepository
import com.starfarer.companionoverlay.repository.TutorialSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Core application dependencies — things that live for the entire app lifecycle.
 */
val appModule = module {

    // Shared OkHttpClient with sensible timeouts. The gateway WebSocket and
    // avatar downloads share this instance's connection pool and dispatcher.
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Event coordinator — replaces static instance pattern
    single { OverlayCoordinator() }

    // Settings repository — wraps SharedPreferences with proper abstraction.
    // Doubles as the GatewayConfig (server URL, token, device identity).
    single {
        SettingsRepository(
            settingsPrefs = get(named("settings")),
            securePrefs = get(named("auth")),
            presetProvider = { get<PresetRepository>().getActive() }
        )
    }

    // Tutorial sandbox settings — fresh per tutorial run; radial-toggle writes stay in memory
    factory {
        TutorialSettings(
            settingsPrefs = get(named("settings")),
            securePrefs = get(named("auth")),
            presetProvider = { get<PresetRepository>().getActive() }
        )
    }

    // Presence gateway — the app's only conversation channel. A singleton so
    // the offline queue and reconnect state survive service restarts; the
    // overlay service start()s/stop()s it around its own lifetime. Listener
    // callbacks are posted to the main thread.
    single {
        val mainHandler = Handler(Looper.getMainLooper())
        GatewayClient(
            config = get<SettingsRepository>() as GatewayConfig,
            httpClient = get(),
            callbackExecutor = Executor { mainHandler.post(it) },
        )
    }

    // Versioned avatar sprite cache (filesDir/avatar), synced on welcome.
    single {
        AvatarRepository(
            baseDir = File(androidContext().filesDir, "avatar"),
            httpClient = get(),
            config = get<SettingsRepository>() as GatewayConfig,
        )
    }
}
