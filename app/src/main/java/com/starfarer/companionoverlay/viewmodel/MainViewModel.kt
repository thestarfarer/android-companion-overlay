package com.starfarer.companionoverlay.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.ClaudeAuth
import com.starfarer.companionoverlay.DebugLog
import com.starfarer.companionoverlay.event.OverlayCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for MainActivity.
 * 
 * Owns:
 * - Character preset list and selection
 * - Auth state observation
 * - Overlay running state observation
 * 
 * Sprite animation still lives in the Activity since it directly manipulates
 * ImageViews and Bitmaps (not easily ViewModel-able without significant abstraction).
 */
class MainViewModel(
    application: Application,
    private val claudeAuth: ClaudeAuth,
    private val coordinator: OverlayCoordinator
) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "MainVM"
    }
    
    // ══════════════════════════════════════════════════════════════════════
    // State
    // ══════════════════════════════════════════════════════════════════════
    
    data class UiState(
        val presets: List<CharacterPreset> = emptyList(),
        val activeIndex: Int = 0,
        val authState: AuthState = AuthState.NotConnected,
        val overlayRunning: Boolean = false
    ) {
        val activePreset: CharacterPreset?
            get() = presets.getOrNull(activeIndex)
    }
    
    sealed class AuthState {
        data object NotConnected : AuthState()
        data object Waiting : AuthState()
        data class Connected(val expiresAt: Long) : AuthState()
        data object Expired : AuthState()
    }
    
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    
    private val context get() = getApplication<Application>()
    
    init {
        loadPresets()
        observeOverlayState()
    }
    
    // ══════════════════════════════════════════════════════════════════════
    // Preset Management
    // ══════════════════════════════════════════════════════════════════════
    
    fun loadPresets() {
        val presets = CharacterPreset.loadAll(context)
        val activeId = CharacterPreset.getActiveId(context)
        val activeIndex = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        
        _state.update { it.copy(presets = presets, activeIndex = activeIndex) }
        DebugLog.log(TAG, "Loaded ${presets.size} presets, active: $activeIndex")
    }
    
    fun selectPreset(index: Int) {
        val presets = _state.value.presets
        if (index < 0 || index >= presets.size) return
        
        CharacterPreset.setActiveId(context, presets[index].id)
        _state.update { it.copy(activeIndex = index) }
    }
    
    fun updateActivePreset(transform: (CharacterPreset) -> CharacterPreset) {
        val current = _state.value.activePreset ?: return
        val updated = transform(current)
        CharacterPreset.save(context, updated)
        loadPresets() // Reload to get updated list
    }
    
    fun createPreset(): CharacterPreset {
        val source = _state.value.activePreset ?: CharacterPreset()
        val newPreset = CharacterPreset(
            name = "${source.name} copy",
            systemPrompt = source.systemPrompt,
            userMessage = source.userMessage,
            idleSpriteUri = source.idleSpriteUri,
            walkSpriteUri = source.walkSpriteUri,
            idleFrameCount = source.idleFrameCount,
            walkFrameCount = source.walkFrameCount
        )
        CharacterPreset.save(context, newPreset)
        loadPresets()
        
        // Select the new preset
        val newIndex = _state.value.presets.indexOfFirst { it.id == newPreset.id }
        if (newIndex >= 0) selectPreset(newIndex)
        
        return newPreset
    }
    
    fun deleteActivePreset() {
        val preset = _state.value.activePreset ?: return
        if (_state.value.presets.size <= 1) return // Don't delete last preset
        
        CharacterPreset.delete(context, preset.id)
        loadPresets()
    }
    
    // ══════════════════════════════════════════════════════════════════════
    // Auth
    // ══════════════════════════════════════════════════════════════════════
    
    fun refreshAuthState() {
        val authState = when {
            claudeAuth.isWaitingForCallback() -> AuthState.Waiting
            claudeAuth.isAuthenticated() -> {
                val expiresAt = claudeAuth.getExpiresAt()
                if (System.currentTimeMillis() > expiresAt) {
                    AuthState.Expired
                } else {
                    AuthState.Connected(expiresAt)
                }
            }
            else -> AuthState.NotConnected
        }
        _state.update { it.copy(authState = authState) }
    }
    
    fun logout() {
        claudeAuth.logout()
        refreshAuthState()
    }
    
    fun cancelAuth() {
        claudeAuth.cancelAuth()
        refreshAuthState()
    }
    
    suspend fun refreshToken(): Result<Unit> {
        val result = claudeAuth.refreshToken()
        refreshAuthState()
        return result
    }
    
    // ══════════════════════════════════════════════════════════════════════
    // Overlay State
    // ══════════════════════════════════════════════════════════════════════
    
    private fun observeOverlayState() {
        viewModelScope.launch {
            coordinator.overlayRunning.collect { running ->
                _state.update { it.copy(overlayRunning = running) }
            }
        }
    }
    
    // ══════════════════════════════════════════════════════════════════════
    // Sprite Loading (utility for Activity)
    // ══════════════════════════════════════════════════════════════════════
    
    fun loadSpriteSheet(customUri: String?, customAsset: String, defaultAsset: String): Bitmap? {
        if (customUri != null) {
            try {
                context.contentResolver.openInputStream(Uri.parse(customUri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { return it }
                }
            } catch (_: Exception) {}
        }
        try {
            context.assets.open(customAsset).use { stream ->
                BitmapFactory.decodeStream(stream)?.let { return it }
            }
        } catch (_: Exception) {}
        return try {
            context.assets.open(defaultAsset).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }
}
