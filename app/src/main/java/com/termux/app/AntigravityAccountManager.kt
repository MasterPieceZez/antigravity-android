package com.termux.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AccountProfile(
    val id: String,
    val email: String,
    val addedAt: Long,
    val lastUsedAt: Long,
    val quotaExhaustedAt: Long = 0L,
    val cooldownUntil: Long = 0L,
    val isActive: Boolean = false
) {
    fun isInCooldown(now: Long = System.currentTimeMillis()): Boolean =
        cooldownUntil > now
}

/**
 * Manages multiple Antigravity Google OAuth accounts for instant switching
 * and automatic failover when an account's quota is exhausted.
 */
interface CredentialCipher {
    fun encrypt(plain: ByteArray): Result<ByteArray>
    fun decrypt(data: ByteArray): Result<ByteArray>
}

/**
 * Encrypts saved multi-account OAuth snapshots with an Android Keystore AES-256-GCM key
 * so shell processes sharing the app UID cannot read inactive account credentials in plaintext.
 * Transparently reads legacy unencrypted snapshots for seamless upgrades.
 */
class AndroidKeystoreCredentialCipher(
    private val keyAlias: String = "agy_account_snapshot_v1"
) : CredentialCipher {
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): Result<ByteArray> {
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain)
            val out = ByteArray(MAGIC.size + 1 + iv.size + ct.size)
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
            out[MAGIC.size] = iv.size.toByte()
            System.arraycopy(iv, 0, out, MAGIC.size + 1, iv.size)
            System.arraycopy(ct, 0, out, MAGIC.size + 1 + iv.size, ct.size)
            out
        }
    }

    override fun decrypt(data: ByteArray): Result<ByteArray> {
        if (data.size <= MAGIC.size + 2) return Result.success(data)
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) return Result.success(data) // Legacy plaintext snapshot
        }
        return runCatching {
            val ivLen = data[MAGIC.size].toInt() and 0xFF
            if (ivLen < 12 || data.size <= MAGIC.size + 1 + ivLen) {
                throw IllegalArgumentException("Corrupted encrypted credential header")
            }
            val iv = data.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + ivLen)
            val ct = data.copyOfRange(MAGIC.size + 1 + ivLen, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ct)
        }
    }

    companion object {
        private val MAGIC = byteArrayOf(
            'A'.code.toByte(), 'G'.code.toByte(), 'Y'.code.toByte(), 'E'.code.toByte(),
            'N'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte(), 0
        )
    }
}

class AntigravityAccountManager(
    private val homeDir: File = File(AntigravityEngine.HOME_DIR),
    private val accountsRoot: File = File(AntigravityEngine.BRIDGE_DIR, "accounts"),
    private val cipher: CredentialCipher? = null
) {
    private val lock = Any()

    private val primaryTokenFile: File
        get() = File(homeDir, ".gemini/antigravity-cli/antigravity-oauth-token")

    private val jetskiTokenFile: File
        get() = File(homeDir, ".gemini/jetski-standalone-oauth-token")

    private val userScopeFile: File
        get() = File(homeDir, ".config/antigravity/credential-user-scope")

    fun isAutoSwitchEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_SWITCH_QUOTA, true)
    }

    fun setAutoSwitchEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SWITCH_QUOTA, enabled)
            .apply()
    }

    fun getActiveEmail(): String? {
        return readEmailFromTokenFile(primaryTokenFile)
            ?: readEmailFromTokenFile(jetskiTokenFile)
    }

    /**
     * Captures the currently signed-in account's OAuth token files into its snapshot directory.
     * Returns the captured AccountProfile, or null if no active account is signed in.
     */
    fun captureActiveAccount(): AccountProfile? = synchronized(lock) {
        val email = getActiveEmail() ?: return null
        val id = accountIdForEmail(email)
        val dir = File(accountsRoot, id)
        ensureSecureDir(accountsRoot)
        ensureSecureDir(dir)

        val okPrimary = copyCredentialIfPresent(primaryTokenFile, File(dir, FILE_PRIMARY_TOKEN))
        val okJetski = copyCredentialIfPresent(jetskiTokenFile, File(dir, FILE_JETSKI_TOKEN))
        val okScope = copyCredentialIfPresent(userScopeFile, File(dir, FILE_USER_SCOPE))
        if (!okPrimary || !okJetski || !okScope) return null

        val now = System.currentTimeMillis()
        val existing = readMeta(dir, id, email)
        val updated = AccountProfile(
            id = id,
            email = email,
            addedAt = existing?.addedAt ?: now,
            lastUsedAt = now,
            quotaExhaustedAt = existing?.quotaExhaustedAt ?: 0L,
            cooldownUntil = existing?.cooldownUntil ?: 0L,
            isActive = true
        )
        writeMeta(dir, updated)
        return updated
    }

    fun listAccounts(): List<AccountProfile> = synchronized(lock) {
        captureActiveAccountLockedIfPresent()
        val activeEmail = getActiveEmail()?.lowercase()
        val dirs = accountsRoot.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val list = ArrayList<AccountProfile>(dirs.size)
        for (dir in dirs) {
            val meta = readMeta(dir, dir.name, null) ?: continue
            val hasCreds = File(dir, FILE_PRIMARY_TOKEN).isFile || File(dir, FILE_JETSKI_TOKEN).isFile
            if (!hasCreds) continue
            val isAct = activeEmail != null && meta.email.equals(activeEmail, ignoreCase = true)
            list.add(meta.copy(isActive = isAct))
        }
        return list.sortedWith(
            compareByDescending<AccountProfile> { it.isActive }
                .thenByDescending { it.lastUsedAt }
                .thenBy { it.email.lowercase() }
        )
    }

    /**
     * Instantly switches active credentials to the target account.
     * Caller should restart Antigravity Hub and reload the WebView afterward.
     */
    fun switchToAccount(accountId: String, clearCooldown: Boolean = true): AccountProfile? = synchronized(lock) {
        captureActiveAccountLockedIfPresent()
        val targetDir = File(accountsRoot, accountId)
        if (!targetDir.isDirectory) return null
        val targetMeta = readMeta(targetDir, accountId, null) ?: return null

        val snapPrimary = File(targetDir, FILE_PRIMARY_TOKEN)
        val snapJetski = File(targetDir, FILE_JETSKI_TOKEN)
        val snapScope = File(targetDir, FILE_USER_SCOPE)
        if (!snapPrimary.isFile && !snapJetski.isFile) return null

        // Decrypt all present snapshot files into memory first so a Keystore failure
        // never corrupts or partially overwrites the currently active OAuth files.
        val decPrimary = readDecryptedCredential(snapPrimary) ?: return null
        val decJetski = readDecryptedCredential(snapJetski) ?: return null
        val decScope = readDecryptedCredential(snapScope) ?: return null

        applyDecryptedCredential(decPrimary, primaryTokenFile)
        applyDecryptedCredential(decJetski, jetskiTokenFile)
        applyDecryptedCredential(decScope, userScopeFile)

        val now = System.currentTimeMillis()
        val updated = targetMeta.copy(
            lastUsedAt = now,
            quotaExhaustedAt = if (clearCooldown) 0L else targetMeta.quotaExhaustedAt,
            cooldownUntil = if (clearCooldown) 0L else targetMeta.cooldownUntil,
            isActive = true
        )
        writeMeta(targetDir, updated)
        return updated
    }

    /**
     * Saves the current account and clears active OAuth files so the Hub prompts
     * for a fresh Google sign-in for a new account.
     */
    fun prepareForNewAccountLogin(): AccountProfile? = synchronized(lock) {
        val saved = captureActiveAccountLockedIfPresent()
        runCatching { primaryTokenFile.delete() }
        runCatching { jetskiTokenFile.delete() }
        runCatching { userScopeFile.delete() }
        return saved
    }

    fun removeAccount(accountId: String): Boolean = synchronized(lock) {
        val activeEmail = getActiveEmail()?.lowercase()
        val targetDir = File(accountsRoot, accountId)
        if (!targetDir.isDirectory) return false
        val meta = readMeta(targetDir, accountId, null)
        if (meta != null && activeEmail != null && meta.email.equals(activeEmail, ignoreCase = true)) {
            return false
        }
        return targetDir.deleteRecursively()
    }

    /**
     * Marks the current active account as quota-exhausted and selects the best available
     * fallback account (if any) without swapping active credential files on disk.
     * Callers should stop the Hub process before calling [switchToAccount].
     */
    fun markCurrentQuotaExhaustedAndSelectNext(
        cooldownMs: Long = DEFAULT_QUOTA_COOLDOWN_MS
    ): AccountProfile? = synchronized(lock) {
        val current = captureActiveAccountLockedIfPresent() ?: return null
        val now = System.currentTimeMillis()
        val currentDir = File(accountsRoot, current.id)
        val exhausted = current.copy(
            quotaExhaustedAt = now,
            cooldownUntil = now + cooldownMs,
            isActive = true
        )
        writeMeta(currentDir, exhausted)

        val candidates = (accountsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .mapNotNull { dir ->
                val meta = readMeta(dir, dir.name, null) ?: return@mapNotNull null
                val hasCreds = File(dir, FILE_PRIMARY_TOKEN).isFile || File(dir, FILE_JETSKI_TOKEN).isFile
                if (!hasCreds || meta.id == current.id) null else meta
            }

        if (candidates.isEmpty()) return null

        // Prefer accounts whose cooldown has expired; if none, return null so we don't thrash
        val ready = candidates.filter { !it.isInCooldown(now) }
        return ready.minWithOrNull(
            compareBy<AccountProfile> { it.quotaExhaustedAt }.thenByDescending { it.lastUsedAt }
        )
    }

    /**
     * Marks the current active account as quota-exhausted and immediately switches to the
     * best available fallback account.
     */
    @Deprecated(
        message = "Use markCurrentQuotaExhaustedAndSelectNext() and switchToAccount() after stopping the Hub",
        replaceWith = ReplaceWith("markCurrentQuotaExhaustedAndSelectNext(cooldownMs)")
    )
    fun markCurrentQuotaExhaustedAndPickNext(
        cooldownMs: Long = DEFAULT_QUOTA_COOLDOWN_MS
    ): AccountProfile? {
        val chosen = markCurrentQuotaExhaustedAndSelectNext(cooldownMs) ?: return null
        return switchToAccount(chosen.id, clearCooldown = false)
    }

    private fun captureActiveAccountLockedIfPresent(): AccountProfile? {
        val email = getActiveEmail() ?: return null
        val id = accountIdForEmail(email)
        val dir = File(accountsRoot, id)
        ensureSecureDir(accountsRoot)
        ensureSecureDir(dir)

        val okPrimary = copyCredentialIfPresent(primaryTokenFile, File(dir, FILE_PRIMARY_TOKEN))
        val okJetski = copyCredentialIfPresent(jetskiTokenFile, File(dir, FILE_JETSKI_TOKEN))
        val okScope = copyCredentialIfPresent(userScopeFile, File(dir, FILE_USER_SCOPE))
        if (!okPrimary || !okJetski || !okScope) return null

        val now = System.currentTimeMillis()
        val existing = readMeta(dir, id, email)
        val updated = AccountProfile(
            id = id,
            email = email,
            addedAt = existing?.addedAt ?: now,
            lastUsedAt = existing?.lastUsedAt ?: now,
            quotaExhaustedAt = existing?.quotaExhaustedAt ?: 0L,
            cooldownUntil = existing?.cooldownUntil ?: 0L,
            isActive = true
        )
        writeMeta(dir, updated)
        return updated
    }

    private fun readEmailFromTokenFile(file: File, encrypted: Boolean = false): String? {
        if (!file.isFile) return null
        return runCatching {
            val rawBytes = file.readBytes()
            val plainBytes = if (encrypted && cipher != null) {
                cipher.decrypt(rawBytes).getOrNull() ?: return@runCatching null
            } else {
                rawBytes
            }
            val content = plainBytes.toString(Charsets.UTF_8)
            EMAIL_JSON_REGEX.find(content)?.groupValues?.getOrNull(1)?.let { unescapeJson(it) }?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    private fun readMeta(dir: File, fallbackId: String, fallbackEmail: String?): AccountProfile? {
        val metaFile = File(dir, FILE_META)
        if (!metaFile.isFile) {
            val email = fallbackEmail ?: readEmailFromTokenFile(File(dir, FILE_PRIMARY_TOKEN), encrypted = true)
                ?: readEmailFromTokenFile(File(dir, FILE_JETSKI_TOKEN), encrypted = true)
                ?: return null
            val now = System.currentTimeMillis()
            return AccountProfile(
                id = fallbackId,
                email = email,
                addedAt = now,
                lastUsedAt = now
            )
        }
        return runCatching {
            val raw = metaFile.readText()
            val email = extractJsonString(raw, "email")?.trim()?.ifBlank { null } ?: fallbackEmail ?: ""
            if (email.isBlank()) return@runCatching null
            val now = System.currentTimeMillis()
            AccountProfile(
                id = extractJsonString(raw, "id")?.ifBlank { fallbackId } ?: fallbackId,
                email = email,
                addedAt = extractJsonLong(raw, "addedAt") ?: now,
                lastUsedAt = extractJsonLong(raw, "lastUsedAt") ?: now,
                quotaExhaustedAt = extractJsonLong(raw, "quotaExhaustedAt") ?: 0L,
                cooldownUntil = extractJsonLong(raw, "cooldownUntil") ?: 0L
            )
        }.getOrNull()
    }

    private fun writeMeta(dir: File, profile: AccountProfile) {
        ensureSecureDir(dir)
        val json = buildString {
            append('{')
            append("\"id\":\"").append(escapeJson(profile.id)).append("\",")
            append("\"email\":\"").append(escapeJson(profile.email)).append("\",")
            append("\"addedAt\":").append(profile.addedAt).append(',')
            append("\"lastUsedAt\":").append(profile.lastUsedAt).append(',')
            append("\"quotaExhaustedAt\":").append(profile.quotaExhaustedAt).append(',')
            append("\"cooldownUntil\":").append(profile.cooldownUntil)
            append('}')
        }
        atomicWriteText(File(dir, FILE_META), json)
    }

    private fun extractJsonString(raw: String, key: String): String? {
        val regex = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
        val rawVal = regex.find(raw)?.groupValues?.getOrNull(1) ?: return null
        return unescapeJson(rawVal)
    }

    private fun extractJsonLong(raw: String, key: String): Long? {
        val regex = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
        return regex.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescapeJson(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (hex != null) {
                                sb.append(hex.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(next); i += 2
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun copyCredentialIfPresent(src: File, dst: File): Boolean {
        if (!src.isFile) return true
        val bytes = runCatching { src.readBytes() }.getOrNull() ?: return false
        val out = if (cipher != null) {
            cipher.encrypt(bytes).getOrNull() ?: return false
        } else {
            bytes
        }
        return runCatching { atomicWriteBytes(dst, out); true }.getOrDefault(false)
    }

    private sealed class DecryptedCredential {
        object Absent : DecryptedCredential()
        class Present(val bytes: ByteArray) : DecryptedCredential()
    }

    private fun readDecryptedCredential(src: File): DecryptedCredential? {
        if (!src.isFile) return DecryptedCredential.Absent
        val bytes = runCatching { src.readBytes() }.getOrNull() ?: return null
        val plain = if (cipher != null) {
            cipher.decrypt(bytes).getOrNull() ?: return null
        } else {
            bytes
        }
        return DecryptedCredential.Present(plain)
    }

    private fun applyDecryptedCredential(cred: DecryptedCredential, dst: File) {
        when (cred) {
            is DecryptedCredential.Present -> atomicWriteBytes(dst, cred.bytes)
            is DecryptedCredential.Absent -> runCatching { dst.delete() }
        }
    }

    private fun atomicWriteText(target: File, content: String) {
        atomicWriteBytes(target, content.toByteArray(Charsets.UTF_8))
    }

    private fun atomicWriteBytes(target: File, bytes: ByteArray) {
        target.parentFile?.let { ensureSecureDir(it) }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(ByteArray(0))
        runCatching { Os.chmod(tmp.absolutePath, 0b110000000) } // 0600
        tmp.writeBytes(bytes)
        runCatching { Os.chmod(tmp.absolutePath, 0b110000000) } // 0600
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            runCatching { Os.chmod(target.absolutePath, 0b110000000) }
            tmp.delete()
        }
    }

    private fun ensureSecureDir(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        runCatching { Os.chmod(dir.absolutePath, 0b111000000) } // 0700
    }

    companion object {
        private const val PREFS_NAME = "antigravity_prefs"
        private const val KEY_AUTO_SWITCH_QUOTA = "auto_switch_quota_exhausted"
        private const val FILE_META = "meta.json"
        private const val FILE_PRIMARY_TOKEN = "antigravity-oauth-token"
        private const val FILE_JETSKI_TOKEN = "jetski-standalone-oauth-token"
        private const val FILE_USER_SCOPE = "credential-user-scope"
        const val DEFAULT_QUOTA_COOLDOWN_MS = 30L * 60L * 1000L
        private val EMAIL_JSON_REGEX = Regex(""""id_token_email"\s*:\s*"([^"]+)"""")

        fun accountIdForEmail(email: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(email.trim().lowercase().toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

        fun isQuotaExhaustedSignal(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val lower = text.lowercase()
            return lower.contains("resource_exhausted") ||
                lower.contains("quota exhausted") ||
                lower.contains("quota exceeded") ||
                lower.contains("exceeded your current quota") ||
                lower.contains("you have reached your") ||
                lower.contains("usage limit reached") ||
                lower.contains("rate limit exceeded") ||
                lower.contains("model quota") ||
                lower.contains("capacity exhausted") ||
                (lower.contains("429") && (lower.contains("quota") || lower.contains("rate") || lower.contains("limit") || lower.contains("too many requests")))
        }
    }
}
