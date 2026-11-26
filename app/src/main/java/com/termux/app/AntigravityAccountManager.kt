package com.termux.app

import android.content.Context
import android.system.Os
import java.io.File
import java.security.MessageDigest

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
class AntigravityAccountManager(
    private val homeDir: File = File(AntigravityEngine.HOME_DIR),
    private val accountsRoot: File = File(AntigravityEngine.BRIDGE_DIR, "accounts")
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

        copyCredentialIfPresent(primaryTokenFile, File(dir, FILE_PRIMARY_TOKEN))
        copyCredentialIfPresent(jetskiTokenFile, File(dir, FILE_JETSKI_TOKEN))
        copyCredentialIfPresent(userScopeFile, File(dir, FILE_USER_SCOPE))

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

        replaceCredentialFile(snapPrimary, primaryTokenFile)
        replaceCredentialFile(snapJetski, jetskiTokenFile)
        replaceCredentialFile(snapScope, userScopeFile)

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
     * Marks the current active account as quota-exhausted and picks the best available
     * fallback account (if any). Returns null if no other eligible account exists.
     */
    fun markCurrentQuotaExhaustedAndPickNext(
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
        val chosen = ready.minWithOrNull(
            compareBy<AccountProfile> { it.quotaExhaustedAt }.thenByDescending { it.lastUsedAt }
        ) ?: return null

        return switchToAccount(chosen.id, clearCooldown = false)
    }

    private fun captureActiveAccountLockedIfPresent(): AccountProfile? {
        val email = getActiveEmail() ?: return null
        val id = accountIdForEmail(email)
        val dir = File(accountsRoot, id)
        ensureSecureDir(accountsRoot)
        ensureSecureDir(dir)

        copyCredentialIfPresent(primaryTokenFile, File(dir, FILE_PRIMARY_TOKEN))
        copyCredentialIfPresent(jetskiTokenFile, File(dir, FILE_JETSKI_TOKEN))
        copyCredentialIfPresent(userScopeFile, File(dir, FILE_USER_SCOPE))

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

    private fun readEmailFromTokenFile(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val content = file.readText()
            EMAIL_JSON_REGEX.find(content)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    private fun readMeta(dir: File, fallbackId: String, fallbackEmail: String?): AccountProfile? {
        val metaFile = File(dir, FILE_META)
        if (!metaFile.isFile) {
            val email = fallbackEmail ?: readEmailFromTokenFile(File(dir, FILE_PRIMARY_TOKEN))
                ?: readEmailFromTokenFile(File(dir, FILE_JETSKI_TOKEN))
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
        return regex.find(raw)?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun extractJsonLong(raw: String, key: String): Long? {
        val regex = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
        return regex.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun copyCredentialIfPresent(src: File, dst: File) {
        if (!src.isFile) return
        val bytes = runCatching { src.readBytes() }.getOrNull() ?: return
        atomicWriteBytes(dst, bytes)
    }

    private fun replaceCredentialFile(src: File, dst: File) {
        if (src.isFile) {
            val bytes = runCatching { src.readBytes() }.getOrNull() ?: return
            atomicWriteBytes(dst, bytes)
        } else {
            runCatching { dst.delete() }
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
