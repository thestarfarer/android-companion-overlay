package com.starfarer.companionoverlay.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.starfarer.companionoverlay.CharacterPreset
import com.starfarer.companionoverlay.SpriteAnimator
import kotlinx.coroutines.*

/**
 * Manages the sprite preview animation in the preset carousel.
 *
 * Separate from [SpriteAnimator] which runs in the overlay service — this
 * one handles the preview thumbnails in the ViewPager on the main screen.
 *
 * ## Frame pre-extraction
 *
 * Previous implementation called [Bitmap.createBitmap] on every animation
 * tick (120 allocations/second — idle + walk at 60fps-equivalent). This
 * version pre-extracts all frames when a sprite sheet is loaded and cycles
 * through the cached array. GC pressure drops from ~120 allocs/sec to zero
 * during animation.
 *
 * ## Async loading
 *
 * Sprite sheets are decoded on [Dispatchers.IO] and frames are extracted
 * there too. The adapter is only updated on the main thread once frames
 * are ready. For typical sprite sheet sizes this is invisible, but it
 * prevents jank if someone ships a high-res sheet or the disk is slow
 * on cold start.
 *
 * @param adapter The pager adapter to push frame updates to
 * @param spriteLoader Function to load a sprite sheet bitmap from URI/assets
 */
class SpritePreviewAnimator(
    private val adapter: PresetPagerAdapter,
    private val spriteLoader: (String?, String, String) -> Bitmap?
) {

    /** Pre-extracted frames and metadata for a single preset position. */
    private data class SpriteCache(
        val idleFrames: List<Bitmap>,
        val walkFrames: List<Bitmap>,
        val sheet: Pair<Bitmap?, Bitmap?> // kept alive so frames aren't recycled
    )

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = mutableMapOf<Int, SpriteCache>()
    private val loading = mutableSetOf<Int>() // positions currently being loaded
    private var currentIdleFrame = 0
    private var currentWalkFrame = 0
    private var animating = false
    private var currentPosition = 0

    private val idleRunnable = object : Runnable {
        override fun run() {
            if (!animating) return
            currentIdleFrame++
            updateCurrentFrame()
            handler.postDelayed(this, SpriteAnimator.IDLE_FRAME_DURATION_MS)
        }
    }

    private val walkRunnable = object : Runnable {
        override fun run() {
            if (!animating) return
            currentWalkFrame++
            updateCurrentFrame()
            handler.postDelayed(this, SpriteAnimator.WALK_FRAME_DURATION_MS)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════

    /** Load and cache sprites for a preset at the given pager position. */
    fun loadForPreset(position: Int, preset: CharacterPreset) {
        currentPosition = position

        if (cache.containsKey(position)) {
            updateCurrentFrame()
            return
        }

        if (position in loading) return // already in flight

        loading.add(position)
        scope.launch {
            val entry = withContext(Dispatchers.IO) {
                val idleSheet = spriteLoader(preset.idleSpriteUri, "custom_idle_sheet.png", "idle_sheet.png")
                val walkSheet = spriteLoader(preset.walkSpriteUri, "custom_walk_sheet.png", "walk_sheet.png")

                val idleCount = preset.idleFrameCount.coerceAtLeast(1)
                val walkCount = preset.walkFrameCount.coerceAtLeast(1)

                SpriteCache(
                    idleFrames = extractAllFrames(idleSheet, idleCount),
                    walkFrames = extractAllFrames(walkSheet, walkCount),
                    sheet = Pair(idleSheet, walkSheet)
                )
            }

            loading.remove(position)
            cache[position] = entry

            if (currentPosition == position) {
                updateCurrentFrame()
            }
        }
    }

    /** Update which pager position is currently visible. */
    fun setCurrentPosition(position: Int) {
        currentPosition = position
        updateCurrentFrame()
    }

    fun start() {
        if (animating) return
        animating = true
        currentIdleFrame = 0
        currentWalkFrame = 0
        handler.postDelayed(idleRunnable, SpriteAnimator.IDLE_FRAME_DURATION_MS)
        handler.postDelayed(walkRunnable, SpriteAnimator.WALK_FRAME_DURATION_MS)
    }

    fun stop() {
        animating = false
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(walkRunnable)
    }

    /** Clear all cached frames. Call when preset list changes. */
    fun clearCache() {
        cache.clear()
        loading.clear()
    }

    fun release() {
        stop()
        scope.cancel()
        clearCache()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Internal
    // ══════════════════════════════════════════════════════════════════════

    private fun updateCurrentFrame() {
        val entry = cache[currentPosition] ?: return

        val idle = if (entry.idleFrames.isNotEmpty())
            entry.idleFrames[currentIdleFrame % entry.idleFrames.size]
        else null

        val walk = if (entry.walkFrames.isNotEmpty())
            entry.walkFrames[currentWalkFrame % entry.walkFrames.size]
        else null

        adapter.updateSpriteFrame(currentPosition, idle, walk)
    }

    /**
     * Pre-extract all frames from a sprite sheet into an array.
     * Each frame is a separate Bitmap — allocated once, reused forever.
     * Called on [Dispatchers.IO].
     */
    private fun extractAllFrames(sheet: Bitmap?, frameCount: Int): List<Bitmap> {
        if (sheet == null) return emptyList()
        val count = frameCount.coerceAtLeast(1)
        val frameWidth = sheet.width / count

        return (0 until count).mapNotNull { i ->
            runCatching {
                Bitmap.createBitmap(sheet, i * frameWidth, 0, frameWidth, sheet.height)
            }.getOrNull()
        }
    }
}
