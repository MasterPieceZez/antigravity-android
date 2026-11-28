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
        assertTrue(LocalBridgeServer.isAllowedOrigin("http://localhost:4401"))
        assertFalse(LocalBridgeServer.isAllowedOrigin("http://localhost:9999"))
        assertFalse(LocalBridgeServer.isAllowedOrigin("file://localhost/etc/passwd"))
        assertFalse(LocalBridgeServer.isAllowedOrigin("https://evil.example.com"))
        assertFalse(LocalBridgeServer.isAllowedOrigin("http://localhost.evil.com"))

        assertFalse(LocalBridgeServer.isAllowedHost(null))
        assertFalse(LocalBridgeServer.isAllowedHost(""))
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
        assertTrue(AntigravityEngine.isNewerVersion("v1.1.0", "v1.0.9.1"))
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

            // Auto-failover when Alice's quota is exhausted -> selects Bob without overwriting active token yet
            val next = mgr.markCurrentQuotaExhaustedAndSelectNext(cooldownMs = 60_000L)
            assertNotNull(next)
            assertEquals("bob@example.com", next!!.email)
            assertEquals("alice@example.com", mgr.getActiveEmail())
            assertTrue(primaryToken.readText().contains("tok_a"))

            // Caller stops Hub first, then switches credentials
            val switchedToBob = mgr.switchToAccount(next.id, clearCooldown = false)
            assertNotNull(switchedToBob)
            assertEquals("bob@example.com", mgr.getActiveEmail())
            assertTrue(primaryToken.readText().contains("tok_b"))

            // If Bob also drains while Alice is in cooldown -> returns null (prevents loop)
            val none = mgr.markCurrentQuotaExhaustedAndSelectNext(cooldownMs = 60_000L)
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

    @Test
    fun engine_detectsNetworkErrorsDuringInstall() {
        assertTrue(AntigravityEngine.isNetworkErrorOutput("curl: (6) Could not resolve host: antigravity.google"))
        assertTrue(AntigravityEngine.isNetworkErrorOutput("E: Failed to fetch https://packages.termux.dev/... Temporary failure in name resolution"))
        assertTrue(AntigravityEngine.isNetworkErrorOutput("curl: (28) Connection timed out after 15000 milliseconds"))
        assertFalse(AntigravityEngine.isNetworkErrorOutput("Installing antigravity CLI v1.2.3..."))
    }

    @Test
    fun hubViewModel_enforcesAtomicStateTransitions() {
        val vm = AntigravityHubViewModel()
        assertFalse(vm.tryBeginReconnect())

        vm.markBootstrapComplete()
        assertTrue(vm.tryBeginBoot())
        assertFalse(vm.tryBeginBoot())
        assertFalse(vm.tryBeginReconnect())

        vm.finishBoot("http://localhost:4400/#t=abc")
        assertEquals(HubPhase.Running("http://localhost:4400/#t=abc"), vm.currentState.phase)
        assertTrue(vm.tryBeginReconnect())
        assertFalse(vm.tryBeginReconnect())
        vm.finishReconnect("http://localhost:4400/#t=def")
        assertEquals("http://localhost:4400/#t=def", vm.currentState.currentHubUrl)

        assertTrue(vm.tryBeginQuotaFailover(nowMs = 20_000L))
        assertFalse(vm.tryBeginAccountSwitch())
        assertFalse(vm.tryBeginQuotaFailover(nowMs = 25_000L))
        vm.finishAccountSwitch("http://localhost:4400/#t=ghi")
        assertFalse(vm.currentState.isSwitchingAccount)
        assertFalse(vm.tryBeginQuotaFailover(nowMs = 25_000L))
        assertTrue(vm.tryBeginQuotaFailover(nowMs = 40_000L))
        vm.finishAccountSwitch(null)

        assertEquals(1, vm.recordRenderCrash(10_000L))
        assertEquals(2, vm.recordRenderCrash(15_000L))
        assertEquals(1, vm.recordRenderCrash(40_000L))
    }

    @Test
    fun engine_parsesCurlProgressAndSpeed() {
        val line = " 42 85.2M   42 35.8M    0     0  3450k      0  0:00:25  0:00:10  0:00:15 3620k"
        val parsed = AntigravityEngine.parseCurlProgressLine(line)
        assertNotNull(parsed)
        assertEquals(42, parsed!!.first)
        assertEquals("3.5 MB/s", parsed.second)

        assertEquals("512 KB/s", AntigravityEngine.formatCurlSpeed("512k"))
        assertEquals("12.4 MB/s", AntigravityEngine.formatCurlSpeed("12.4M"))
        assertEquals("", AntigravityEngine.formatCurlSpeed("0"))
        assertNull(AntigravityEngine.parseCurlProgressLine("Installing antigravity CLI..."))
    }

    @Test
    fun engine_replaceSameLengthInFile_handlesChunkBoundary() {
        val f = tempFolder.newFile("boundary.bin")
        val chunkSize = 1024 * 1024
        val prefix = ByteArray(chunkSize - 5) { 'A'.code.toByte() }
        val target = "ineligible".toByteArray(Charsets.US_ASCII)
        val suffix = ByteArray(128) { 'Z'.code.toByte() }
        f.writeBytes(prefix + target + suffix)

        val changed = AntigravityEngine.replaceSameLengthInFile(
            f,
            "ineligible".toByteArray(Charsets.US_ASCII),
            "inexigible".toByteArray(Charsets.US_ASCII)
        )
        assertTrue(changed)
        val result = f.readBytes()
        assertEquals(prefix.size + target.size + suffix.size, result.size)
        assertEquals("inexigible", String(result, prefix.size, target.size, Charsets.US_ASCII))
    }

    @Test
    fun inAppUpdater_parsesSha256Checksums() {
        val hash1 = "6e44a509ca7d29d93e249ab4bc167c142c6bac23606e21db8deaa5b41a671500"
        val hash2 = "CBC66F34C65F698A64446E8DF8C08ACA6E27FC432ED75606C1930EBC4D80E73A"
        val checksums = """
            $hash1  AntigravityStandalone-v1.1.1-arm64-v8a.apk
            $hash2 *AntigravityUpdate-v1.1.1-universal-lite.apk
            not-a-valid-hash  broken.apk
        """.trimIndent()

        assertEquals(
            hash1,
            InAppUpdater.parseSha256ForAsset(checksums, "AntigravityStandalone-v1.1.1-arm64-v8a.apk")
        )
        assertEquals(
            hash2.lowercase(),
            InAppUpdater.parseSha256ForAsset(checksums, "AntigravityUpdate-v1.1.1-universal-lite.apk")
        )
        assertNull(InAppUpdater.parseSha256ForAsset(checksums, "nonexistent.apk"))
        assertNull(InAppUpdater.parseSha256ForAsset(checksums, "broken.apk"))
    }

    @Test
    fun inAppUpdater_pinsRepositoryReleaseUrlsAndRedirectHosts() {
        assertTrue(
            InAppUpdater.isAllowedUpdateUrl(
                "https://github.com/MasterPieceZez/antigravity-android/releases/download/v1.1.2/AntigravityUpdate-v1.1.2-universal-lite.apk"
            )
        )
        assertTrue(
            InAppUpdater.isAllowedUpdateUrl(
                "https://github.com/MasterPieceZez/antigravity-android/releases/download/v1.1.2/SHA256SUMS.txt"
            )
        )
        assertFalse(InAppUpdater.isAllowedUpdateUrl("https://github.com/otheruser/evil/releases/download/v1/app.apk"))
        assertFalse(InAppUpdater.isAllowedUpdateUrl("https://github.com/MasterPieceZez/antigravity-android/releases/download/../evil.apk"))
        assertFalse(InAppUpdater.isAllowedUpdateUrl("http://github.com/MasterPieceZez/antigravity-android/releases/download/v1.1.2/app.apk"))
        assertFalse(InAppUpdater.isAllowedUpdateUrl("https://objects.githubusercontent.com/some/blob"))

        assertTrue(InAppUpdater.isAllowedRedirectUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(InAppUpdater.isAllowedRedirectUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(InAppUpdater.isAllowedRedirectUrl("https://raw.githubusercontent.com/evil/repo/main/app.apk"))
        assertFalse(InAppUpdater.isAllowedRedirectUrl("https://evil.example.com/app.apk"))
    }

    @Test
    fun backupRestoreEngine_estimatesRequiredBackupSpaceWithFloor() {
        assertEquals(128L * 1024L * 1024L, BackupRestoreEngine.estimateRequiredBackupBytes(50L * 1024L * 1024L))
        assertEquals(300L * 1024L * 1024L, BackupRestoreEngine.estimateRequiredBackupBytes(900L * 1024L * 1024L))
    }

    @Test
    fun persianBidiJs_respectsFlagsAndContainsCssAndReactSafeHooks() {
        val bidiOnly = AntigravityJsBridge.buildPersianBidiJs(enableBidi = true, enablePersianUi = false)
        assertTrue(bidiOnly.contains("var enableBidi = true;"))
        assertTrue(bidiOnly.contains("var enablePersianUi = false;"))
        assertTrue(bidiOnly.contains("agy-bidi-style"))
        assertTrue(bidiOnly.contains("Vazirmatn-NL.woff2"))
        assertTrue(bidiOnly.contains("U+0600-06FF"))
        assertTrue(bidiOnly.contains("unicode-bidi: plaintext"))
        assertTrue(bidiOnly.contains("direction: ltr !important"))
        assertTrue(bidiOnly.contains("WeakMap"))
        assertTrue(bidiOnly.contains("MutationObserver"))

        val persianUiOnly = AntigravityJsBridge.buildPersianBidiJs(enableBidi = false, enablePersianUi = true)
        assertTrue(persianUiOnly.contains("var enableBidi = false;"))
        assertTrue(persianUiOnly.contains("var enablePersianUi = true;"))
        assertTrue(persianUiOnly.contains(AntigravityJsBridge.FA_DICT_ASSET_URL))
        assertTrue(persianUiOnly.contains("replace(/\\s+/g, ' ').trim()"))
        assertTrue(persianUiOnly.contains("handleMutations"))
        assertTrue(persianUiOnly.contains("translateDurationFa"))
        assertTrue(persianUiOnly.contains("data-agy-fa-rtl"))
        assertTrue(persianUiOnly.contains("data-agy-kbd"))

        val withInlineDict = AntigravityJsBridge.buildPersianBidiJs(
            enableBidi = true,
            enablePersianUi = true,
            inlineFaJson = """{"Add Context":"افزودن زمینه"}"""
        )
        assertTrue(withInlineDict.contains("""var inlineDict = {"Add Context":"افزودن زمینه"};"""))

        val bothOff = AntigravityJsBridge.buildPersianBidiJs(enableBidi = false, enablePersianUi = false)
        assertTrue(bothOff.contains("var enableBidi = false;"))
        assertTrue(bothOff.contains("var enablePersianUi = false;"))
    }

    @Test
    fun persianDictionaryAsset_containsCoreHubChromeEntries() {
        val candidates = listOf(
            java.io.File("src/main/assets/i18n/fa.json"),
            java.io.File("app/src/main/assets/i18n/fa.json")
        )
        val faFile = candidates.firstOrNull { it.isFile }
        assertNotNull("assets/i18n/fa.json must exist", faFile)
        val json = faFile!!.readText(Charsets.UTF_8)
        assertTrue(json.trim().startsWith("{") && json.trim().endsWith("}"))
        assertTrue(json.contains("\"Pinned Conversations\":"))
        assertTrue(json.contains("\"Ask anything, @ to mention, / for actions\":"))
        assertTrue(json.contains("\"Interface Layout\":"))
        assertTrue(json.contains("\"Global Permissions\":"))
        assertTrue(json.contains("\"Keyboard shortcuts for quick navigation and control.\":"))
        assertTrue(json.contains("\"Notification Preferences\":"))
        assertTrue(json.contains("\"Browser Actuation Permissions\":"))
        assertTrue(json.contains("\"Add Context\":"))
        assertTrue(json.contains("\"Weekly Limit Remaining\":"))
        assertTrue(json.contains("\"Code Preview\":"))
        assertTrue(json.contains("\"پروژه‌ها\""))
    }

    @Test
    fun persianBidiPreferences_haveExpectedDefaultsAndOverrides() {
        assertTrue(AntigravityJsBridge.DEFAULT_BIDI_RTL_ENABLED)
        assertFalse(AntigravityJsBridge.DEFAULT_PERSIAN_UI_ENABLED)

        val backing = HashMap<String, Any?>()
        val fakePrefs = object : android.content.SharedPreferences {
            override fun getAll(): MutableMap<String, *> = backing
            override fun getString(key: String?, defValue: String?): String? = backing[key] as? String ?: defValue
            @Suppress("UNCHECKED_CAST")
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
                backing[key] as? MutableSet<String> ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = backing[key] as? Int ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = backing[key] as? Long ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = backing[key] as? Float ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = backing[key] as? Boolean ?: defValue
            override fun contains(key: String?): Boolean = backing.containsKey(key)
            override fun edit(): android.content.SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        assertTrue(AntigravityJsBridge.isBidiRtlEnabled(fakePrefs))
        assertFalse(AntigravityJsBridge.isPersianUiEnabled(fakePrefs))

        backing[AntigravityJsBridge.KEY_BIDI_RTL_ENABLED] = false
        backing[AntigravityJsBridge.KEY_PERSIAN_UI_ENABLED] = true
        assertFalse(AntigravityJsBridge.isBidiRtlEnabled(fakePrefs))
        assertTrue(AntigravityJsBridge.isPersianUiEnabled(fakePrefs))
    }
}
