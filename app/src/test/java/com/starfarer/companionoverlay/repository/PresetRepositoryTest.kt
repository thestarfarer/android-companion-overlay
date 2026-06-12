package com.starfarer.companionoverlay.repository

import android.content.SharedPreferences
import com.starfarer.companionoverlay.CharacterPreset
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PresetRepository].
 *
 * Simulates SharedPreferences persistence with backing variables so the real
 * serialize/deserialize + cache round-trip is exercised. The editor's
 * putString answers write into the backing vars; getString answers read them
 * back, mimicking a real prefs store across calls.
 */
class PresetRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var repository: PresetRepository

    private val KEY_PRESETS = "character_presets"
    private val KEY_ACTIVE_ID = "active_preset_id"

    // Backing "disk" — what SharedPreferences would actually hold.
    private var storedPresets: String? = null
    private var storedActiveId: String? = null

    private fun preset(
        id: String,
        name: String = "Test $id"
    ): CharacterPreset = CharacterPreset(
        id = id,
        name = name,
        systemPrompt = "sys-$id",
        userMessage = "user-$id"
    )

    @Before
    fun setup() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor

        // Reads return the current backing value for the matching key.
        every { prefs.getString(KEY_PRESETS, any()) } answers { storedPresets }
        every { prefs.getString(KEY_ACTIVE_ID, any()) } answers { storedActiveId }

        // Writes capture into the backing vars, keyed by the first argument.
        every { editor.putString(KEY_PRESETS, any()) } answers {
            storedPresets = secondArg()
            editor
        }
        every { editor.putString(KEY_ACTIVE_ID, any()) } answers {
            storedActiveId = secondArg()
            editor
        }

        repository = PresetRepository(prefs)
    }

    @Test
    fun `loadAll on empty prefs creates and persists a single default preset`() {
        assertNull(storedPresets)

        val result = repository.loadAll()

        assertEquals(1, result.size)
        assertNotNull("default preset should have been persisted", storedPresets)
    }

    @Test
    fun `loadAll returns cached list on second call without re-reading prefs`() {
        repository.loadAll()
        repository.loadAll()

        // Only the first call should hit prefs for the presets key.
        verify(exactly = 1) { prefs.getString(KEY_PRESETS, any()) }
    }

    @Test
    fun `invalidateCache forces a re-read from prefs`() {
        repository.loadAll()
        repository.invalidateCache()
        repository.loadAll()

        verify(exactly = 2) { prefs.getString(KEY_PRESETS, any()) }
    }

    @Test
    fun `save of a new preset appends it and save of existing id replaces in place`() {
        repository.saveAll(listOf(preset("a"), preset("b")))

        // Append a brand-new preset.
        repository.save(preset("c"))
        var all = repository.loadAll()
        assertEquals(3, all.size)
        assertEquals(listOf("a", "b", "c"), all.map { it.id })

        // Replace existing id "b" in place — size unchanged, content updated.
        repository.save(preset("b", name = "UpdatedB"))
        all = repository.loadAll()
        assertEquals(3, all.size)
        assertEquals(listOf("a", "b", "c"), all.map { it.id })
        assertEquals("UpdatedB", all.first { it.id == "b" }.name)
    }

    @Test
    fun `getActive returns the preset whose id matches the stored active id`() {
        repository.saveAll(listOf(preset("a"), preset("b")))
        repository.setActiveId("b")

        val active = repository.getActive()

        assertEquals("b", active.id)
    }

    @Test
    fun `getActive falls back to first preset and sets it active when active id is unknown`() {
        repository.saveAll(listOf(preset("a"), preset("b")))
        repository.setActiveId("nonexistent")

        val active = repository.getActive()

        assertEquals("a", active.id)
        assertEquals("a", storedActiveId)
        assertEquals("a", repository.getActiveId())
    }

    @Test
    fun `delete removes the matching preset and deleting the last preset recreates a default`() {
        repository.saveAll(listOf(preset("a"), preset("b")))

        repository.delete("a")
        var all = repository.loadAll()
        assertEquals(1, all.size)
        assertEquals(listOf("b"), all.map { it.id })

        // Deleting the final remaining preset must recreate a default, never empty.
        repository.delete("b")
        all = repository.loadAll()
        assertEquals(1, all.size)
        assertNotEquals("b", all.first().id)
    }

    @Test
    fun `delete of the active preset reassigns active id to the first remaining preset`() {
        repository.saveAll(listOf(preset("a"), preset("b"), preset("c")))
        repository.setActiveId("a")

        repository.delete("a")

        assertEquals("b", repository.getActiveId())
        assertEquals("b", storedActiveId)
    }

    @Test
    fun `corrupt stored JSON recovers by persisting a fresh default`() {
        storedPresets = "not json {{"

        val result = repository.loadAll()

        assertEquals(1, result.size)
        assertNotNull(storedPresets)
        assertNotEquals("not json {{", storedPresets)
        // The rewritten value must be valid JSON that round-trips back to a list.
        val reloaded = PresetRepository(prefs)
        assertEquals(1, reloaded.loadAll().size)
    }

    @Test
    fun `setActiveId writes through to prefs and updates the cache`() {
        repository.setActiveId("xyz")

        assertEquals("xyz", storedActiveId)
        verify { editor.putString(KEY_ACTIVE_ID, "xyz") }
        // Cache is updated — getActiveId returns it without a fresh prefs read.
        assertEquals("xyz", repository.getActiveId())
        verify(exactly = 0) { prefs.getString(KEY_ACTIVE_ID, any()) }
    }
}
