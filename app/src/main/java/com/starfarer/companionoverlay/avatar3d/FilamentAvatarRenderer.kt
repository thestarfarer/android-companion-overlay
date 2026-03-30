package com.starfarer.companionoverlay.avatar3d

import android.content.Context
import android.view.Choreographer
import android.view.TextureView
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.*
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Renders a 3D avatar using Filament into a TextureView overlay.
 * Transparent background, no Activity needed.
 *
 * IMPORTANT: All Filament initialization is deferred until the
 * Surface is actually ready (onNativeWindowChanged callback).
 * Creating the engine before a valid surface exists = native crash.
 */
class FilamentAvatarRenderer(private val context: Context) {

    companion object {
        init { Utils.init() }
        private const val TAG = "FilamentAvatar"
    }

    // Initialized lazily when surface is ready
    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var filamentView: View? = null
    private var camera: Camera? = null
    private var swapChain: SwapChain? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var materialProvider: MaterialProvider? = null
    private var filamentAsset: FilamentAsset? = null

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
        isOpaque = false
    }

    // Animation
    private var running = false
    private var swayTime = 0.0
    private var breathTime = 0.0
    private var blinkTimer = 0.0
    private var nextBlink = Random.nextDouble(3.0, 7.0)
    private var blinkPhase = -1.0

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            render()
        }
    }

    fun createTextureView(): TextureView {
        val sv = TextureView(context).apply {
        }

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                android.util.Log.i(TAG, "Surface ready — initializing Filament")
                // THIS is when we can safely create the engine
                initFilament(surface)
            }

            override fun onDetachedFromSurface() {
                android.util.Log.i(TAG, "Surface detached")
                swapChain?.let { engine?.destroySwapChain(it) }
                swapChain = null
            }

            override fun onResized(width: Int, height: Int) {
                filamentView?.viewport = Viewport(0, 0, width, height)
                android.util.Log.d(TAG, "Resized: ${width}x${height}")
            }
        }

        uiHelper.attachTo(sv)
        return sv
    }

    private fun initFilament(surface: android.view.Surface) {
        try {
            // Create engine
            val eng = Engine.create()
            engine = eng

            renderer = eng.createRenderer()
            scene = eng.createScene()
            filamentView = eng.createView().apply {
                blendMode = View.BlendMode.TRANSLUCENT
                antiAliasing = View.AntiAliasing.FXAA
            }
            camera = eng.createCamera(eng.entityManager.create()).apply {
                setProjection(35.0, 400.0 / 600.0, 0.05, 50.0, Camera.Fov.VERTICAL)
                lookAt(
                    0.0, 0.8, 2.5,
                    0.0, 0.6, 0.0,
                    0.0, 1.0, 0.0
                )
            }

            filamentView?.scene = scene
            filamentView?.camera = camera
            scene?.skybox = null

            renderer?.clearOptions = renderer!!.clearOptions.apply { clear = true }

            // Swap chain with transparency
            swapChain = eng.createSwapChain(surface, SwapChainFlags.CONFIG_TRANSPARENT)

            // Lighting
            val sunlight = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(1.0f, 0.97f, 0.94f)
                .intensity(80000f)
                .direction(0.5f, -1.0f, -0.5f)
                .castShadows(false)
                .build(eng, sunlight)
            scene?.addEntity(sunlight)

            val fill = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(0.85f, 0.88f, 1.0f)
                .intensity(30000f)
                .direction(-0.5f, -0.5f, -0.5f)
                .castShadows(false)
                .build(eng, fill)
            scene?.addEntity(fill)

            // Load model
            loadModel(eng)

            android.util.Log.i(TAG, "Filament initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Filament init failed: ${e.message}", e)
        }
    }

    private fun loadModel(eng: Engine) {
        materialProvider = UbershaderProvider(eng)
        assetLoader = AssetLoader(eng, materialProvider!!, EntityManager.get())
        resourceLoader = ResourceLoader(eng, true)

        val buffer = context.assets.open("models/avatar.glb").use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).apply {
                put(bytes)
                flip()
            }
        }

        filamentAsset = assetLoader!!.createAsset(buffer)
        filamentAsset?.let { asset ->
            resourceLoader!!.loadResources(asset)
            asset.releaseSourceData()
            scene?.addEntities(asset.entities)

            // Rotate 180° to face camera
            val tcm = eng.transformManager
            val root = tcm.getInstance(asset.root)
            tcm.setTransform(root, floatArrayOf(
                -1f, 0f,  0f, 0f,
                 0f, 1f,  0f, 0f,
                 0f, 0f, -1f, 0f,
                 0f, 0f,  0f, 1f
            ))

            android.util.Log.i(TAG, "Model loaded: ${asset.entities.size} entities, root=${asset.root}")
        }
    }

    fun start() {
        running = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun render() {
        val eng = engine ?: return
        val sc = swapChain ?: return
        val r = renderer ?: return
        val v = filamentView ?: return

        // Procedural animation
        val delta = 1.0 / 60.0
        updateAnimation(eng, delta)

        // Render frame
        if (r.beginFrame(sc, System.nanoTime())) {
            r.render(v)
            r.endFrame()
        }
    }

    private fun updateAnimation(eng: Engine, delta: Double) {
        val asset = filamentAsset ?: return

        // Idle sway
        swayTime += delta * 0.7
        val sway = sin(swayTime) * 0.015 + sin(swayTime * 0.37) * 0.006

        // Breathing
        breathTime += delta * 1.1
        val breath = (sin(breathTime) * 0.7 + sin(breathTime * 2.0) * 0.3) * 0.004

        // Apply transform
        val tcm = eng.transformManager
        val root = tcm.getInstance(asset.root)
        if (root != 0) {
            val cosZ = cos(sway).toFloat()
            val sinZ = sin(sway).toFloat()
            val scaleY = (1.0 + breath).toFloat()
            tcm.setTransform(root, floatArrayOf(
                -cosZ,  sinZ,           0f, 0f,
                 sinZ,  cosZ * scaleY,  0f, 0f,
                  0f,   0f,            -1f, 0f,
                  0f,   0f,             0f, 1f
            ))
        }

        // Blink (morph target if available)
        blinkTimer += delta
        if (blinkPhase >= 0) {
            blinkPhase += delta / 0.15
            if (blinkPhase >= 1.0) {
                blinkPhase = -1.0
                nextBlink = Random.nextDouble(3.0, 7.0)
                blinkTimer = 0.0
            }
        } else if (blinkTimer >= nextBlink) {
            blinkPhase = 0.0
            blinkTimer = 0.0
        }
    }

    fun destroy() {
        stop()
        val eng = engine ?: return

        filamentAsset?.let { asset ->
            scene?.removeEntities(asset.entities)
            assetLoader?.destroyAsset(asset)
        }
        materialProvider?.let {
            it.destroyMaterials()
            (it as? UbershaderProvider)?.destroy()
        }
        resourceLoader?.destroy()
        assetLoader?.destroy()

        swapChain?.let { eng.destroySwapChain(it) }
        filamentView?.let { eng.destroyView(it) }
        scene?.let { eng.destroyScene(it) }
        camera?.let { eng.destroyCameraComponent(it.entity) }
        renderer?.let { eng.destroyRenderer(it) }
        eng.destroy()
        engine = null
    }
}
