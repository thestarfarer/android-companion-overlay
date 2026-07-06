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
    //
    // pingInterval is the WebSocket's liveness heartbeat: readTimeout does NOT
    // apply to a socket after the WS upgrade, so without this a server that
    // restarts (or a half-open connection a proxy/radio never cleanly closes)
    // goes UNDETECTED — the app keeps believing it's connected, never re-hellos,
    // and never picks up the restarted server's state until the app itself is
    // restarted. With it, OkHttp sends WS pings and tears the socket down when a
    // pong doesn't return, which fires onFailure → reconnect → fresh welcome →
    // full re-sync. Ignored for the plain-HTTP avatar downloads on this client.
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
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
