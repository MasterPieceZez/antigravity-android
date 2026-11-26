package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AntigravityCoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sanitizeFileName_stripsTraversalAndUnsafeChars() {
        assertEquals("passwd", AntigravityFileHelper.sanitizeFileName("../../etc/passwd"))
        assertEquals("shell.sh", AntigravityFileHelper.sanitizeFileName("..\\..\\shell.sh"))
        assertEquals("my_report__1_.pdf", AntigravityFileHelper.sanitizeFileName("my report (1).pdf"))
        assertFalse(AntigravityFileHelper.sanitizeFileName("...hidden").startsWith("."))
    }

    @Test
    fun uniqueFile_avoidsSilentOverwrite() {
        val dir = tempFolder.newFolder("downloads")
        val first = AntigravityFileHelper.uniqueFile(dir, "export.zip")
        assertEquals("export.zip", first.name)
        first.writeText("v1")

        val second = AntigravityFileHelper.uniqueFile(dir, "export.zip")
        assertEquals("export (1).zip", second.name)
        second.writeText("v2")

        val third = AntigravityFileHelper.uniqueFile(dir, "export.zip")
        assertEquals("export (2).zip", third.name)
    }

    @Test
    fun parseHubUrlFromText_extractsLatestAndNormalizesLoopback() {
        val log = """
            Starting Antigravity Hub...
            Listening on http://127.0.0.1:4400/#t=oldtoken123
            Restarted: http://127.0.0.1:4400/#t=fresh_token-456
        """.trimIndent()
        assertEquals(
            "http://localhost:4400/#t=fresh_token-456",
            AntigravityEngine.parseHubUrlFromText(log)
        )
        assertNull(AntigravityEngine.parseHubUrlFromText("no hub url here"))
    }

    @Test
    fun validateTarEntryPath_rejectsAbsoluteAndTraversalPaths() {
        assertTrue(BackupRestoreEngine.isSafeTarEntryPath("./usr/bin/bash"))
        assertTrue(BackupRestoreEngine.isSafeTarEntryPath("home/.local/bin/agy"))
        assertFalse(BackupRestoreEngine.isSafeTarEntryPath("/etc/passwd"))
        assertFalse(BackupRestoreEngine.isSafeTarEntryPath("../outside"))
        assertFalse(BackupRestoreEngine.isSafeTarEntryPath("./usr/../../escape"))
        assertFalse(BackupRestoreEngine.isSafeTarEntryPath("home\\..\\evil"))
    }

    @Test
    fun localBridgeServer_enforcesLoopbackOriginAndConstantTimeToken() {
        assertTrue(LocalBridgeServer.isAllowedOrigin(null))
        assertTrue(LocalBridgeServer.isAllowedOrigin("http://localhost:4400"))
        assertTrue(LocalBridgeServer.isAllowedOrigin("http://127.0.0.1:4400"))
        assertFalse(LocalBridgeServer.isAllowedOrigin("file://localhost/etc/passwd"))
        assertFalse(LocalBridgeServer.isAllowedOrigin("https://evil.example.com"))
        assertFalse(LocalBridgeServer.isAllowedOrigin("http://localhost.evil.com"))

        assertTrue(LocalBridgeServer.isValidToken("secret-token-123", "secret-token-123"))
        assertFalse(LocalBridgeServer.isValidToken("wrong-token", "secret-token-123"))
        assertFalse(LocalBridgeServer.isValidToken("", "secret-token-123"))
        assertFalse(LocalBridgeServer.isValidToken(null, "secret-token-123"))
    }

    @Test
    fun semverComparison_handlesNumericAndPrereleaseVersions() {
        assertTrue(AntigravityEngine.isNewerVersion("v1.0.10", "v1.0.2"))
        assertTrue(AntigravityEngine.isNewerVersion("v1.1.0", "1.0.9"))
        assertTrue(AntigravityEngine.isNewerVersion("v1.0.1", "v1.0.1-rc1"))
        assertFalse(AntigravityEngine.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AntigravityEngine.isNewerVersion("v1.0.0-beta", "v1.0.0"))
        assertFalse(AntigravityEngine.isNewerVersion("v0.9.9", "v1.0.0"))
    }

    @Test
    fun validateArchiveLinkTarget_blocksEscapesAndAllowsSafeTargets() {
        assertTrue(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/bin/sh", "dash"))
        assertTrue(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/lib/foo/bar", "../baz"))
        assertTrue(BackupRestoreEngine.isSafeArchiveLinkTarget("./home/storage/shared", "/storage/emulated/0"))
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/evil", "../../etc/passwd"))
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/evil", "/etc/passwd"))
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/evil", "/storage/../../etc/passwd"))
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/evil", "/data/data/com.agy.os/../shared_prefs/p"))
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/evil", "../secret", isHardLink = true))
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("./usr/evil", "/data/data/com.agy.os/files/usr/bin/sh", isHardLink = true))
    }

    @Test
    fun trustedHubUri_requiresLoopbackAndHubPort() {
        assertTrue(AntigravityFileHelper.isTrustedHubUri("http://localhost:4400/#t=abc"))
        assertTrue(AntigravityFileHelper.isTrustedHubUri("http://127.0.0.1:4400/"))
        assertFalse(AntigravityFileHelper.isTrustedHubUri("http://localhost:3000/"))
        assertFalse(AntigravityFileHelper.isTrustedHubUri("http://127.0.0.1:8080/"))
        assertFalse(AntigravityFileHelper.isTrustedHubUri("file://localhost:4400/etc/passwd"))
        assertFalse(AntigravityFileHelper.isTrustedHubUri("https://evil.example.com:4400/"))
    }
}
