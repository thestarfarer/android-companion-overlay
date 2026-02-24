package com.starfarer.companionoverlay.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.starfarer.companionoverlay.repository.PresetRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Storage dependencies — SharedPreferences and preset persistence.
 */
val storageModule = module {
    
    single<SharedPreferences>(named("settings")) {
        androidContext().getSharedPreferences("companion_prompts", Context.MODE_PRIVATE)
    }
    
    single<SharedPreferences>(named("auth")) {
        val masterKey = MasterKey.Builder(androidContext())
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        EncryptedSharedPreferences.create(
            androidContext(),
            "companion_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    // PresetRepository uses the same prefs file as settings (companion_prompts)
    single { PresetRepository(get(named("settings"))) }
}
