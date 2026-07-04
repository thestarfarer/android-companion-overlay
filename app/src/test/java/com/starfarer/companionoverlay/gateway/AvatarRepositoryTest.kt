package com.starfarer.companionoverlay.gateway

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class AvatarRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var tmpRoot: File
    private lateinit var repo: AvatarRepository
    private val httpClient = OkHttpClient()

    private val idleBytes = "IDLE-SHEET-BYTES".toByteArray()
    private val walkBytes = "WALK-SHEET-BYTES".toByteArray()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestJson(version: String, corruptIdleSha: Boolean = false) = """
        {"version":"$version","sprites":{
          "idle":{"file":"idle_sheet.png","frames":6,"bytes":${idleBytes.size},"sha256":"${if (corruptIdleSha) "0".repeat(64) else sha(idleBytes)}"},
          "walk":{"file":"walk_sheet.png","frames":4,"bytes":${walkBytes.size},"sha256":"${sha(walkBytes)}"}
        }}
    """.trimIndent()

    @Volatile private var manifestBody: String = ""
    @Volatile private var lastAuthHeader: String? = null

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("avatar-test").toFile()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                lastAuthHeader = request.getHeader("Authorization")
                return when (request.path) {
                    "/avatar/manifest" -> MockResponse().setBody(manifestBody)
                    "/avatar/asset/idle_sheet.png" -> MockResponse().setBody(Buffer().write(idleBytes))
                    "/avatar/asset/walk_sheet.png" -> MockResponse().setBody(Buffer().write(walkBytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val config = object : GatewayConfig {
            override val serverUrl = server.url("/").toString()
            override val token = "avatar-token"
            override val deviceId = "test"
            override val deviceName = "test"
        }
        repo = AvatarRepository(File(tmpRoot, "avatar"), httpClient, config)
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmpRoot.deleteRecursively()
    }

    @Test
    fun `fresh sync downloads sprites, verifies hashes, and exposes frame counts`() {
        manifestBody = manifestJson("v-one")
        assertNull(repo.cachedVersion())

        assertTrue(repo.syncIfNeeded("v-one"))

        assertEquals("v-one", repo.cachedVersion())
        val idle = repo.sprite("idle")!!
        assertEquals(6, idle.frames)
        assertTrue(idle.file.readBytes().contentEquals(idleBytes))
        assertEquals(4, repo.sprite("walk")!!.frames)
        assertEquals("Bearer avatar-token", lastAuthHeader)
    }

    @Test
    fun `matching version is a no-op`() {
        manifestBody = manifestJson("v-one")
        assertTrue(repo.syncIfNeeded("v-one"))
        assertFalse(repo.syncIfNeeded("v-one"))
        assertFalse(repo.syncIfNeeded(null))
    }

    @Test
    fun `sha mismatch aborts and preserves the old cache`() {
        manifestBody = manifestJson("v-one")
        assertTrue(repo.syncIfNeeded("v-one"))

        manifestBody = manifestJson("v-two", corruptIdleSha = true)
        assertFalse(repo.syncIfNeeded("v-two"))

        // Old cache intact.
        assertEquals("v-one", repo.cachedVersion())
        assertNotNull(repo.sprite("idle"))
    }

    @Test
    fun `no cache and unreachable server leaves sprite lookup null`() {
        server.shutdown()
        assertFalse(repo.syncIfNeeded("v-one"))
        assertNull(repo.sprite("idle"))
        assertNull(repo.cachedVersion())
    }
}
