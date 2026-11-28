package com.termux.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AntigravityConfigTest {

    @Test
    fun ensureDefaultPermissionPreset_seedsTurboOnlyWhenUnset() {
        val tempHome = Files.createTempDirectory("agy_preset_test").toFile()
        try {
            assertTrue(AntigravityEngine.ensureDefaultPermissionPreset(tempHome))
            val cfg = File(tempHome, ".gemini/config/config.json")
            assertTrue(cfg.isFile)
            val seeded = JSONObject(cfg.readText())
            assertEquals(
                "AGENT_PERMISSION_PRESET_TURBO",
                seeded.getJSONObject("userSettings").getString("permissionPreset")
            )

            // Existing user preference must NEVER be overwritten
            val customJson = """{"userSettings":{"permissionPreset":"AGENT_PERMISSION_PRESET_DEFAULT","voiceName":"Arlo"}}"""
            cfg.writeText(customJson)
            assertFalse(AntigravityEngine.ensureDefaultPermissionPreset(tempHome))
            val after = JSONObject(cfg.readText())
            assertEquals(
                "AGENT_PERMISSION_PRESET_DEFAULT",
                after.getJSONObject("userSettings").getString("permissionPreset")
            )
            assertEquals("Arlo", after.getJSONObject("userSettings").getString("voiceName"))
        } finally {
            tempHome.deleteRecursively()
        }
    }
}
