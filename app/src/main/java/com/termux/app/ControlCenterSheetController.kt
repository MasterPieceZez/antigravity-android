package com.termux.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.termux.BuildConfig
import com.termux.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Builds and wires the Control Center BottomSheetDialog (health status, multi-account switching,
 * power/notification toggles, CLI reinstall, GitHub update check, backup/restore).
 */
class ControlCenterSheetController(
    private val activity: AppCompatActivity,
    private val prefsName: String,
    private val keyKeepScreenOn: String,
    private val keyCpuWakeLock: String,
    private val accountManager: AntigravityAccountManager,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onApplyKeepScreenOn(enabled: Boolean)
        fun onApplyCpuWakeLock(enabled: Boolean)
        fun onSendTestNotification()
        fun onAddAccount()
        fun onSwitchAccount(account: AccountProfile)
        fun onToggleRegionBypass(enabled: Boolean)
        fun onReloadWebView()
        fun onRestartHub()
        fun onToggleTerminal()
        fun onShowLogs()
        fun onClearWebViewCache()
        fun onReinstallCliConfirmed()
        fun onRunBackup(mode: BackupMode, progressBar: ProgressBar, statusText: TextView)
        fun onPromptRestore(progressBar: ProgressBar, statusText: TextView, dialog: BottomSheetDialog)
        fun onCopyText(label: String, text: String)
        fun onSheetDismissed(dialog: BottomSheetDialog)
    }

    fun show() {
        val dialog = BottomSheetDialog(activity, R.style.Theme_Antigravity_BottomSheet)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_antigravity_controls, null)
        dialog.setContentView(view)

        val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        val dotEngine = view.findViewById<View>(R.id.dot_engine)
        val txtEngine = view.findViewById<TextView>(R.id.txt_status_engine)
        val dotHub = view.findViewById<View>(R.id.dot_hub)
        val txtHub = view.findViewById<TextView>(R.id.txt_status_hub)

        val txtActiveAccount = view.findViewById<TextView>(R.id.txt_active_account)
        val btnAddAccount = view.findViewById<View>(R.id.sheet_btn_add_account)
        val accountsListContainer = view.findViewById<LinearLayout>(R.id.sheet_accounts_list)
        val switchAutoSwitch = view.findViewById<Switch>(R.id.switch_auto_switch_quota)

        switchAutoSwitch.isChecked = accountManager.isAutoSwitchEnabled(activity)
        switchAutoSwitch.setOnCheckedChangeListener { _, checked ->
            accountManager.setAutoSwitchEnabled(activity, checked)
        }

        val switchRegionBypass = view.findViewById<Switch>(R.id.switch_region_bypass)
        switchRegionBypass.isChecked = AntigravityEngine.isRegionBypassEnabled(activity)
        switchRegionBypass.setOnCheckedChangeListener { _, checked ->
            dialog.dismiss()
            callbacks.onToggleRegionBypass(checked)
        }
        btnAddAccount.setOnClickListener {
            dialog.dismiss()
            callbacks.onAddAccount()
        }

        val switchScreenOn = view.findViewById<Switch>(R.id.switch_keep_screen_on)
        val switchCpuWake = view.findViewById<Switch>(R.id.switch_cpu_wakelock)
        val switchNotifyDone = view.findViewById<Switch>(R.id.switch_notify_done)
        val switchNotifyFg = view.findViewById<Switch>(R.id.switch_notify_foreground)

        switchScreenOn.isChecked = prefs.getBoolean(keyKeepScreenOn, false)
        switchCpuWake.isChecked = prefs.getBoolean(keyCpuWakeLock, false)
        switchNotifyDone.isChecked = AntigravityNotifier.isNotifyDoneEnabled(activity)
        switchNotifyFg.isChecked = AntigravityNotifier.isNotifyForegroundEnabled(activity)

        switchScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(keyKeepScreenOn, checked).apply()
            callbacks.onApplyKeepScreenOn(checked)
        }
        switchCpuWake.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(keyCpuWakeLock, checked).apply()
            callbacks.onApplyCpuWakeLock(checked)
        }
        switchNotifyDone.setOnCheckedChangeListener { _, checked ->
            AntigravityNotifier.setNotifyDoneEnabled(activity, checked)
        }
        switchNotifyFg.setOnCheckedChangeListener { _, checked ->
            AntigravityNotifier.setNotifyForegroundEnabled(activity, checked)
        }

        view.findViewById<View>(R.id.sheet_btn_test_notif).setOnClickListener {
            callbacks.onSendTestNotification()
        }

        val progressBar = view.findViewById<ProgressBar>(R.id.sheet_backup_progress)
        val statusText = view.findViewById<TextView>(R.id.sheet_backup_status)

        var sheetDismissed = false
        dialog.setOnDismissListener {
            sheetDismissed = true
            callbacks.onSheetDismissed(dialog)
        }

        activity.lifecycleScope.launch {
            val health = AntigravityEngine.getHealth()
            val accounts = withContext(Dispatchers.IO) { accountManager.listAccounts() }
            if (activity.isFinishing || activity.isDestroyed || sheetDismissed) return@launch
            dotEngine.setBackgroundResource(if (health.engineReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtEngine.text = if (health.engineReady) {
                "Embedded Engine: Active (${Build.SUPPORTED_ABIS.firstOrNull() ?: "Linux"})"
            } else {
                "Embedded Engine: Initializing"
            }

            dotHub.setBackgroundResource(if (health.hubReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtHub.text = if (health.hubReady) "Antigravity Hub (4400): Online" else "Antigravity Hub (4400): Offline"

            renderAccountsInSheet(txtActiveAccount, accountsListContainer, accounts, dialog)
        }

        view.findViewById<View>(R.id.sheet_btn_reload).setOnClickListener {
            dialog.dismiss()
            callbacks.onReloadWebView()
        }

        view.findViewById<View>(R.id.sheet_btn_restart).setOnClickListener {
            dialog.dismiss()
            callbacks.onRestartHub()
        }

        view.findViewById<View>(R.id.sheet_btn_terminal).setOnClickListener {
            dialog.dismiss()
            callbacks.onToggleTerminal()
        }

        view.findViewById<View>(R.id.sheet_btn_logs).setOnClickListener {
            dialog.dismiss()
            callbacks.onShowLogs()
        }

        view.findViewById<View>(R.id.sheet_btn_clear_cache).setOnClickListener {
            dialog.dismiss()
            callbacks.onClearWebViewCache()
        }

        view.findViewById<View>(R.id.sheet_btn_reinstall_cli).setOnClickListener {
            dialog.dismiss()
            if (AntigravityEngine.isInstallRunning()) {
                Toast.makeText(activity, "Installation already in progress...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(activity)
                .setTitle("Reinstall / Update CLI")
                .setMessage("Stop Antigravity Hub and run the official Antigravity CLI installer from https://antigravity.google/cli/install.sh?")
                .setPositiveButton("Reinstall") { _, _ ->
                    callbacks.onReinstallCliConfirmed()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        view.findViewById<View>(R.id.sheet_btn_check_update).setOnClickListener {
            Toast.makeText(activity, "Checking GitHub Releases...", Toast.LENGTH_SHORT).show()
            activity.lifecycleScope.launch {
                val res = AntigravityEngine.checkLatestRelease()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                res.onSuccess { info ->
                    val current = "v${BuildConfig.VERSION_NAME}"
                    if (info.tagName.isNotBlank() && AntigravityEngine.isNewerVersion(info.tagName, current)) {
                        val dlUrl = info.downloadApkUrl ?: info.releaseUrl
                        val msg = if (info.isLiteApk) {
                            "Installed: $current\nLatest: ${info.tagName}\n\nDownload the lightweight universal-lite updater APK?"
                        } else {
                            "Installed: $current\nLatest: ${info.tagName}\n\nDownload the updated APK from GitHub Releases?"
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Update Available (${info.tagName})")
                            .setMessage(msg)
                            .setPositiveButton("Download") { _, _ ->
                                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl))) }
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        Toast.makeText(activity, "You're up to date ($current)", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(activity, "Update check failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<View>(R.id.sheet_btn_phantom_fix).setOnClickListener {
            val cmd = "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""
            AlertDialog.Builder(activity)
                .setTitle("Android 12+ Phantom Process Fix")
                .setMessage("If Android kills heavy builds (npm/cargo/gradle) after >32 child processes, run this once via ADB or Shizuku:\n\n$cmd")
                .setPositiveButton("Copy Command") { _, _ ->
                    callbacks.onCopyText("Phantom Process Fix", cmd)
                    Toast.makeText(activity, "Copied ADB command to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        view.findViewById<View>(R.id.sheet_btn_backup_full).setOnClickListener {
            callbacks.onRunBackup(BackupMode.FULL, progressBar, statusText)
        }

        view.findViewById<View>(R.id.sheet_btn_backup_packages).setOnClickListener {
            callbacks.onRunBackup(BackupMode.PACKAGES_ONLY, progressBar, statusText)
        }

        view.findViewById<View>(R.id.sheet_btn_restore).setOnClickListener {
            callbacks.onPromptRestore(progressBar, statusText, dialog)
        }

        dialog.show()
    }

    private fun renderAccountsInSheet(
        txtActiveAccount: TextView,
        container: LinearLayout,
        accounts: List<AccountProfile>,
        dialog: BottomSheetDialog
    ) {
        container.removeAllViews()
        val active = accounts.firstOrNull { it.isActive }
        txtActiveAccount.text = if (active != null) "Active: ${active.email}" else "No active account signed in"

        if (accounts.isEmpty()) {
            val emptyTv = TextView(activity).apply {
                text = "Sign in to Antigravity, then tap '+ Add Account' to save multiple accounts for 1-tap switching."
                setTextColor(activity.getColor(R.color.agy_text_secondary))
                textSize = 12f
            }
            container.addView(emptyTv)
            return
        }

        val dp = activity.resources.displayMetrics.density
        val now = System.currentTimeMillis()
        for (acct in accounts) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.agy_btn_secondary)
                val padH = (12 * dp).toInt()
                val padV = (8 * dp).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * dp).toInt() }
            }

            val label = TextView(activity).apply {
                val statusSuffix = when {
                    acct.isActive -> "  • Active"
                    acct.isInCooldown(now) -> "  • Cooldown"
                    else -> ""
                }
                text = "${acct.email}$statusSuffix"
                setTextColor(activity.getColor(if (acct.isActive) R.color.agy_accent else R.color.agy_text_primary))
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            if (!acct.isActive) {
                val btnSwitch = TextView(activity).apply {
                    text = "Switch"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.agy_btn_primary)
                    textSize = 11.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
                    setOnClickListener {
                        dialog.dismiss()
                        callbacks.onSwitchAccount(acct)
                    }
                }
                row.addView(btnSwitch)

                val btnDelete = TextView(activity).apply {
                    text = "✕"
                    setTextColor(activity.getColor(R.color.agy_text_secondary))
                    textSize = 13f
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                    setOnClickListener {
                        activity.lifecycleScope.launch {
                            val removed = withContext(Dispatchers.IO) { accountManager.removeAccount(acct.id) }
                            if (removed && !activity.isFinishing && !activity.isDestroyed) {
                                val updated = withContext(Dispatchers.IO) { accountManager.listAccounts() }
                                renderAccountsInSheet(txtActiveAccount, container, updated, dialog)
                                Toast.makeText(activity, "Removed ${acct.email}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                row.addView(btnDelete)
            }
            container.addView(row)
        }
    }
}
