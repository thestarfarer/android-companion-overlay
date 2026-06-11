package com.starfarer.companionoverlay.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.starfarer.companionoverlay.DebugLog
import com.starfarer.companionoverlay.repository.PresetRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Storage dependencies — SharedPreferences and preset persistence.
 */
val storageModule = module {

    single<SharedPreferences>(named("settings")) {
        androidContext().getSharedPreferences("companion_prompts", Context.MODE_PRIVATE)
    }

    single<SharedPreferences>(named("auth")) {
        createAuthPrefs(androidContext())
    }

    // PresetRepository uses the same prefs file as settings (companion_prompts)
    single { PresetRepository(get(named("settings"))) }
}

/**
 * The Tink keyset inside companion_auth.xml is wrapped by an Android Keystore
 * master key that never leaves the device, so a prefs file that arrives via
 * backup restore or device transfer (or survives a wiped Keystore) cannot be
 * decrypted and create() throws. This dependency sits under SettingsRepository,
 * which nearly every component injects — without recovery the app crash-loops
 * until the user clears app data. Backup rules exclude the file
 * (res/xml/data_extraction_rules.xml), but stale copies from older installs
 * still exist in the wild: on failure, delete the file and start fresh.
 * Stored credentials are lost; the app stays bootable.
 */
private fun createAuthPrefs(context: Context): SharedPreferences {
    fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "companion_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    return try {
        create()
    } catch (e: GeneralSecurityException) {
        DebugLog.log("Storage", "Auth prefs undecryptable (restored from backup?) — recreating: ${e.message}")
        context.deleteSharedPreferences("companion_auth")
        create()
    } catch (e: IOException) {
        DebugLog.log("Storage", "Auth prefs unreadable — recreating: ${e.message}")
        context.deleteSharedPreferences("companion_auth")
        create()
    }
}
