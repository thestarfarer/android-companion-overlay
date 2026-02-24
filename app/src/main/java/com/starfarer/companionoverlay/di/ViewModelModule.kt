package com.starfarer.companionoverlay.di

import com.starfarer.companionoverlay.viewmodel.MainViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * ViewModel definitions for Koin.
 */
val viewModelModule = module {
    
    viewModel {
        MainViewModel(
            application = androidApplication(),
            claudeAuth = get(),
            coordinator = get(),
            presetRepository = get()
        )
    }
}
