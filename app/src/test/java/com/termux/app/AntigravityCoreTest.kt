package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        assertTrue(LocalBridgeServer.isAllowedHost(null))
        assertTrue(LocalBridgeServer.isAllowedHost("127.0.0.1:4401"))
        assertTrue(LocalBridgeServer.isAllowedHost("localhost:4401"))
        assertTrue(LocalBridgeServer.isAllowedHost("localhost"))
        assertFalse(LocalBridgeServer.isAllowedHost("evil.example.com:4401"))
        assertFalse(LocalBridgeServer.isAllowedHost("127.0.0.1:8080"))

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
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("a/", "../etc/passwd"))
        assertFalse(BackupRestoreEngine.isSafeArchiveLinkTarget("a/.", "../etc/passwd"))
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

    @Test
    fun accountManager_multiAccountSwitchAndQuotaFailover() {
        val tempHome = java.nio.file.Files.createTempDirectory("agy_acct_test").toFile()
        try {
            val bridgeAccounts = java.io.File(tempHome, ".agy_bridge/accounts")
            val mgr = AntigravityAccountManager(homeDir = tempHome, accountsRoot = bridgeAccounts)
            val primaryToken = java.io.File(tempHome, ".gemini/antigravity-cli/antigravity-oauth-token")
            val jetskiToken = java.io.File(tempHome, ".gemini/jetski-standalone-oauth-token")
            primaryToken.parentFile?.mkdirs()
            jetskiToken.parentFile?.mkdirs()

            primaryToken.writeText("""{"id_token_email":"alice@example.com","token":{"access_token":"tok_a"}}""")
            jetskiToken.writeText("""{"id_token_email":"alice@example.com","token":{"access_token":"j_a"}}""")
            val alice = mgr.captureActiveAccount()
            assertNotNull(alice)
            assertEquals("alice@example.com", alice!!.email)

            // Prepare for new login, then sign in as Bob
            mgr.prepareForNewAccountLogin()
            assertFalse(primaryToken.exists())
            primaryToken.writeText("""{"id_token_email":"bob@example.com","token":{"access_token":"tok_b"}}""")
            val bob = mgr.captureActiveAccount()
            assertNotNull(bob)
            assertEquals("bob@example.com", bob!!.email)

            val all = mgr.listAccounts()
            assertEquals(2, all.size)
            assertTrue(all.first { it.email == "bob@example.com" }.isActive)

            // Instant switch back to Alice
            val switched = mgr.switchToAccount(alice.id)
            assertNotNull(switched)
            assertEquals("alice@example.com", mgr.getActiveEmail())
            assertTrue(primaryToken.readText().contains("tok_a"))

            // Auto-failover when Alice's quota is exhausted -> picks Bob
            val next = mgr.markCurrentQuotaExhaustedAndPickNext(cooldownMs = 60_000L)
            assertNotNull(next)
            assertEquals("bob@example.com", next!!.email)
            assertEquals("bob@example.com", mgr.getActiveEmail())

            // If Bob also drains while Alice is in cooldown -> returns null (prevents loop)
            val none = mgr.markCurrentQuotaExhaustedAndPickNext(cooldownMs = 60_000L)
            assertNull(none)
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun accountManager_detectsQuotaSignals() {
        assertTrue(AntigravityAccountManager.isQuotaExhaustedSignal("Error: RESOURCE_EXHAUSTED"))
        assertTrue(AntigravityAccountManager.isQuotaExhaustedSignal("You have reached your usage limit"))
        assertTrue(AntigravityAccountManager.isQuotaExhaustedSignal("HTTP 429 rate limit exceeded"))
        assertFalse(AntigravityAccountManager.isQuotaExhaustedSignal("Build succeeded in 429 ms"))
        assertFalse(AntigravityAccountManager.isQuotaExhaustedSignal("Hello world"))
    }

    @Test
    fun swapStagedDirs_packagesOnlyPreservesExistingHomeFiles() {
        val filesRoot = tempFolder.newFolder("files_root")
        val existingUsr = java.io.File(filesRoot, "usr").apply { mkdirs() }
        java.io.File(existingUsr, "old_bin").writeText("old")
        val existingHome = java.io.File(filesRoot, "home").apply { mkdirs() }
        java.io.File(existingHome, "my_project.txt").writeText("keep me")
        java.io.File(existingHome, ".local/bin").apply { mkdirs() }
        java.io.File(existingHome, ".local/bin/agy").writeText("old-agy")

        val staging = tempFolder.newFolder("staging_pkg")
        java.io.File(staging, "usr").apply { mkdirs() }
        java.io.File(staging, "usr/new_bin").writeText("new")
        java.io.File(staging, "home/.local/bin").apply { mkdirs() }
        java.io.File(staging, "home/.local/bin/agy").writeText("new-agy")
        java.io.File(staging, "home/.agents").apply { mkdirs() }
        java.io.File(staging, "home/.agents/agent.json").writeText("{}")

        val res = BackupRestoreEngine.swapStagedDirs(filesRoot, staging)
        assertTrue(res.isSuccess)
        assertEquals("keep me", java.io.File(filesRoot, "home/my_project.txt").readText())
        assertEquals("new-agy", java.io.File(filesRoot, "home/.local/bin/agy").readText())
        assertTrue(java.io.File(filesRoot, "home/.agents/agent.json").isFile)
        assertTrue(java.io.File(filesRoot, "usr/new_bin").isFile)
        assertFalse(java.io.File(filesRoot, "usr/old_bin").exists())
    }

    @Test
    fun swapStagedDirs_fullRestoreReplacesHomeDirectory() {
        val filesRoot = tempFolder.newFolder("files_root_full")
        val existingHome = java.io.File(filesRoot, "home").apply { mkdirs() }
        java.io.File(existingHome, "stale.txt").writeText("old")

        val staging = tempFolder.newFolder("staging_full")
        java.io.File(staging, "home").apply { mkdirs() }
        java.io.File(staging, "home/restored.txt").writeText("fresh")
        java.io.File(staging, "home/.local").apply { mkdirs() }

        val res = BackupRestoreEngine.swapStagedDirs(filesRoot, staging)
        assertTrue(res.isSuccess)
        assertEquals("fresh", java.io.File(filesRoot, "home/restored.txt").readText())
        assertFalse(java.io.File(filesRoot, "home/stale.txt").exists())
    }

    @Test
    fun accountManager_encryptsSnapshotsAndMigratesLegacyPlaintext() {
        val tempHome = java.nio.file.Files.createTempDirectory("agy_enc_test").toFile()
        try {
            val bridgeAccounts = java.io.File(tempHome, ".agy_bridge/accounts")
            val xorCipher = object : CredentialCipher {
                private val header = "ENC:".toByteArray(Charsets.UTF_8)
                override fun encrypt(plain: ByteArray): Result<ByteArray> {
                    val out = ByteArray(header.size + plain.size)
                    System.arraycopy(header, 0, out, 0, header.size)
                    for (i in plain.indices) out[header.size + i] = (plain[i].toInt() xor 0x5A).toByte()
                    return Result.success(out)
                }
                override fun decrypt(data: ByteArray): Result<ByteArray> {
                    if (data.size <= header.size) return Result.success(data)
                    for (i in header.indices) if (data[i] != header[i]) return Result.success(data)
                    val out = ByteArray(data.size - header.size)
                    for (i in out.indices) out[i] = (data[header.size + i].toInt() xor 0x5A).toByte()
                    return Result.success(out)
                }
            }
            val mgr = AntigravityAccountManager(homeDir = tempHome, accountsRoot = bridgeAccounts, cipher = xorCipher)
            val primaryToken = java.io.File(tempHome, ".gemini/antigravity-cli/antigravity-oauth-token")
            primaryToken.parentFile?.mkdirs()

            val plainToken = """{"id_token_email":"enc@example.com","token":{"access_token":"secret_xyz"}}"""
            primaryToken.writeText(plainToken)
            val captured = mgr.captureActiveAccount()
            assertNotNull(captured)

            val snapFile = java.io.File(bridgeAccounts, "${captured!!.id}/antigravity-oauth-token")
            assertTrue(snapFile.isFile)
            assertFalse("Snapshot on disk must not contain plaintext token", snapFile.readText().contains("secret_xyz"))

            // Clear active token and switch back -> active token is decrypted
            mgr.prepareForNewAccountLogin()
            assertFalse(primaryToken.exists())
            val restored = mgr.switchToAccount(captured.id)
            assertNotNull(restored)
            assertEquals("enc@example.com", mgr.getActiveEmail())
            assertTrue(primaryToken.readText().contains("secret_xyz"))

            // Legacy plaintext snapshot in another account directory still loads seamlessly
            val legacyId = AntigravityAccountManager.accountIdForEmail("legacy@example.com")
            val legacyDir = java.io.File(bridgeAccounts, legacyId).apply { mkdirs() }
            java.io.File(legacyDir, "antigravity-oauth-token")
                .writeText("""{"id_token_email":"legacy@example.com","token":{"access_token":"legacy_tok"}}""")
            val switchedLegacy = mgr.switchToAccount(legacyId)
            assertNotNull(switchedLegacy)
            assertEquals("legacy@example.com", mgr.getActiveEmail())
            assertTrue(primaryToken.readText().contains("legacy_tok"))
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun accountManager_abortsSwitchOnDecryptFailureWithoutCorruptingActiveToken() {
        val tempHome = java.nio.file.Files.createTempDirectory("agy_dec_fail_test").toFile()
        try {
            val bridgeAccounts = java.io.File(tempHome, ".agy_bridge/accounts")
            val failingDecryptCipher = object : CredentialCipher {
                override fun encrypt(plain: ByteArray): Result<ByteArray> =
                    Result.success("AGYENC1\u0000".toByteArray(Charsets.UTF_8) + plain)
                override fun decrypt(data: ByteArray): Result<ByteArray> =
                    Result.failure(IllegalStateException("Keystore key invalidated"))
            }
            val mgr = AntigravityAccountManager(homeDir = tempHome, accountsRoot = bridgeAccounts, cipher = failingDecryptCipher)
            val primaryToken = java.io.File(tempHome, ".gemini/antigravity-cli/antigravity-oauth-token")
            primaryToken.parentFile?.mkdirs()

            val activeContent = """{"id_token_email":"active@example.com","token":{"access_token":"keep_me"}}"""
            primaryToken.writeText(activeContent)

            val staleId = AntigravityAccountManager.accountIdForEmail("stale@example.com")
            val staleDir = java.io.File(bridgeAccounts, staleId).apply { mkdirs() }
            java.io.File(staleDir, "meta.json").writeText("""{"id":"$staleId","email":"stale@example.com","addedAt":1,"lastUsedAt":1,"quotaExhaustedAt":0,"cooldownUntil":0}""")
            java.io.File(staleDir, "antigravity-oauth-token").writeBytes("AGYENC1\u0000corrupt_ciphertext".toByteArray(Charsets.UTF_8))

            val switched = mgr.switchToAccount(staleId)
            assertNull("Switch must abort when Keystore decryption fails", switched)
            assertEquals("Active token must remain intact", activeContent, primaryToken.readText())
        } finally {
            tempHome.deleteRecursively()
        }
    }

    @Test
    fun accountManager_doesNotWritePlaintextWhenEncryptionFails() {
        val tempHome = java.nio.file.Files.createTempDirectory("agy_enc_fail_test").toFile()
        try {
            val bridgeAccounts = java.io.File(tempHome, ".agy_bridge/accounts")
            val failingEncryptCipher = object : CredentialCipher {
                override fun encrypt(plain: ByteArray): Result<ByteArray> =
                    Result.failure(IllegalStateException("Keystore unavailable"))
                override fun decrypt(data: ByteArray): Result<ByteArray> = Result.success(data)
            }
            val mgr = AntigravityAccountManager(homeDir = tempHome, accountsRoot = bridgeAccounts, cipher = failingEncryptCipher)
            val primaryToken = java.io.File(tempHome, ".gemini/antigravity-cli/antigravity-oauth-token")
            primaryToken.parentFile?.mkdirs()
            primaryToken.writeText("""{"id_token_email":"sec@example.com","token":{"access_token":"never_plaintext"}}""")

            val captured = mgr.captureActiveAccount()
            assertNull("Capture must fail cleanly instead of writing plaintext", captured)
            val expectedId = AntigravityAccountManager.accountIdForEmail("sec@example.com")
            assertFalse(java.io.File(bridgeAccounts, "$expectedId/antigravity-oauth-token").exists())
        } finally {
            tempHome.deleteRecursively()
        }
    }
}
