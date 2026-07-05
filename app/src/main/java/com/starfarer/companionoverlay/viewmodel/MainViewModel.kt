package com.starfarer.companionoverlay.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.DebugLog
import com.starfarer.companionoverlay.event.OverlayCoordinator
import com.starfarer.companionoverlay.repository.PresetRepository
import com.starfarer.companionoverlay.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for MainActivity.
 *
 * Owns:
 * - Character preset list and selection
 * - Gateway configuration state (is a Nexus server set up?)
 * - Overlay running state observation
 *
 * Preset loading runs on [Dispatchers.IO] to avoid blocking the main thread
 * with SharedPreferences reads and JSON deserialization. The in-memory cache
 * in [PresetRepository] makes subsequent reads fast, but the first load
 * (cold start, after cache invalidation) still hits disk.
 */
class MainViewModel(
    application: Application,
    private val settings: SettingsRepository,
    private val coordinator: OverlayCoordinator,
    private val presetRepository: PresetRepository
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
        val gateway: GatewayState = GatewayState.NotConfigured,
        val overlayRunning: Boolean = false
    ) {
        val activePreset: CharacterPreset?
            get() = presets.getOrNull(activeIndex)
    }

    /** Static configuration state — live connectivity is the service's business. */
    sealed class GatewayState {
        data object NotConfigured : GatewayState()
        data object TokenMissing : GatewayState()
        data class Configured(val serverUrl: String) : GatewayState()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val context get() = getApplication<Application>()

    init {
        loadPresets()
        observeOverlayState()
        refreshGatewayState()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Preset Management
    // ══════════════════════════════════════════════════════════════════════

    fun loadPresets() {
        viewModelScope.launch {
            val (presets, activeId) = withContext(Dispatchers.IO) {
                val p = presetRepository.loadAll()
                val id = presetRepository.getActiveId()
                Pair(p.toList(), id)
            }
            val activeIndex = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)

            _state.update { it.copy(presets = presets, activeIndex = activeIndex) }
            DebugLog.log(TAG, "Loaded ${presets.size} presets, active: $activeIndex")
        }
    }

    fun selectPreset(index: Int) {
        val presets = _state.value.presets
        if (index < 0 || index >= presets.size) return

        presetRepository.setActiveId(presets[index].id)
        _state.update { it.copy(activeIndex = index) }
    }

    fun updateActivePreset(transform: (CharacterPreset) -> CharacterPreset) {
        val current = _state.value.activePreset ?: return
        val updated = transform(current)
        presetRepository.save(updated)
        // Reflect from the (in-memory) cache synchronously — the old async
        // loadPresets() left _state stale for any caller that read it on the
        // next line (e.g. the rename flow), and a rapid second edit could RMW
        // against the stale snapshot.
        syncStateFromRepo(activeId = current.id)
    }

    /** Push the repo's current (cached) presets + active id into UI state. */
    private fun syncStateFromRepo(activeId: String?) {
        val presets = presetRepository.loadAll().toList()
        val index = presets.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        _state.update { it.copy(presets = presets, activeIndex = index) }
    }

    fun createPreset(): CharacterPreset {
        val source = _state.value.activePreset ?: CharacterPreset()
        val newPreset = CharacterPreset(
            name = "${source.name} copy",
            idleSpriteUri = source.idleSpriteUri,
            walkSpriteUri = source.walkSpriteUri,
            idleFrameCount = source.idleFrameCount,
            walkFrameCount = source.walkFrameCount
        )
        presetRepository.save(newPreset)
        presetRepository.setActiveId(newPreset.id)
        // Synchronous select-and-reflect: loadPresets() suspends before
        // updating _state, so createPreset used to return with the new copy
        // NOT selected — the rename dialog that follows then renamed the
        // ORIGINAL preset.
        syncStateFromRepo(activeId = newPreset.id)
        return newPreset
    }

    fun deleteActivePreset() {
        val preset = _state.value.activePreset ?: return
        if (_state.value.presets.size <= 1) return // Don't delete last preset

        presetRepository.delete(preset.id)
        loadPresets()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gateway configuration
    // ══════════════════════════════════════════════════════════════════════

    fun refreshGatewayState() {
        val url = settings.gatewayUrl
        val gateway = when {
            url.isNullOrBlank() -> GatewayState.NotConfigured
            settings.gatewayToken.isNullOrBlank() -> GatewayState.TokenMissing
            else -> GatewayState.Configured(url)
        }
        _state.update { it.copy(gateway = gateway) }
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
