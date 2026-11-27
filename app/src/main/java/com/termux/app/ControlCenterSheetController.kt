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
 * language/BiDi toggles, power/notification toggles, CLI reinstall, GitHub update check, backup/restore).
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
        fun onApplyBidiRtl(enabled: Boolean)
        fun onApplyPersianUi(enabled: Boolean)
        fun onSendTestNotification()
        fun onAddAccount()
        fun onSwitchAccount(account: AccountProfile)
        fun onToggleRegionBypass(enabled: Boolean, onReverted: () -> Unit)
        fun onStartInAppUpdate(info: UpdateReleaseInfo)
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
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = BottomSheetDialog(activity, R.style.Theme_Antigravity_BottomSheet)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_antigravity_controls, null)
        HubThemeSnapshot.load(activity)?.applyToControlSheet(view)
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
            if (checked) {
                callbacks.onToggleRegionBypass(true) {
                    switchRegionBypass.setOnCheckedChangeListener(null)
                    switchRegionBypass.isChecked = false
                    switchRegionBypass.setOnCheckedChangeListener { _, innerChecked ->
                        if (innerChecked) {
                            callbacks.onToggleRegionBypass(true) {
                                dialog.dismiss()
                            }
                        } else {
                            dialog.dismiss()
                            callbacks.onToggleRegionBypass(false) {}
                        }
                    }
                }
            } else {
                dialog.dismiss()
                callbacks.onToggleRegionBypass(false) {}
            }
        }
        btnAddAccount.setOnClickListener {
            dialog.dismiss()
            callbacks.onAddAccount()
        }

        val switchBidiRtl = view.findViewById<Switch>(R.id.switch_bidi_rtl)
        val switchPersianUi = view.findViewById<Switch>(R.id.switch_persian_ui)
        switchBidiRtl.isChecked = AntigravityJsBridge.isBidiRtlEnabled(activity)
        switchPersianUi.isChecked = AntigravityJsBridge.isPersianUiEnabled(activity)

        switchBidiRtl.setOnCheckedChangeListener { _, checked ->
            AntigravityJsBridge.setBidiRtlEnabled(activity, checked)
            callbacks.onApplyBidiRtl(checked)
        }
        switchPersianUi.setOnCheckedChangeListener { _, checked ->
            AntigravityJsBridge.setPersianUiEnabled(activity, checked)
            dialog.dismiss()
            callbacks.onApplyPersianUi(checked)
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
        val progressJob = activity.lifecycleScope.launch {
            BackupRestoreEngine.progressFlow.collect { state ->
                if (!sheetDismissed && state != null) {
                    progressBar.visibility = View.VISIBLE
                    statusText.visibility = View.VISIBLE
                    progressBar.progress = state.first
                    statusText.text = state.second
                }
            }
        }
        dialog.setOnDismissListener {
            sheetDismissed = true
            progressJob.cancel()
            callbacks.onSheetDismissed(dialog)
        }

        activity.lifecycleScope.launch {
            val health = AntigravityEngine.getHealth()
            val accounts = withContext(Dispatchers.IO) { accountManager.listAccounts() }
            if (activity.isFinishing || activity.isDestroyed || sheetDismissed) return@launch
            dotEngine.setBackgroundResource(if (health.engineReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtEngine.text = if (health.engineReady) {
                activity.getString(R.string.agy_status_engine_active, Build.SUPPORTED_ABIS.firstOrNull() ?: "Linux")
            } else {
                activity.getString(R.string.agy_status_engine_init)
            }

            dotHub.setBackgroundResource(if (health.hubReady) R.drawable.agy_dot_ok else R.drawable.agy_dot_off)
            txtHub.text = activity.getString(
                if (health.hubReady) R.string.agy_status_hub_online else R.string.agy_status_hub_offline
            )

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
                Toast.makeText(activity, activity.getString(R.string.agy_toast_install_running), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.agy_dialog_reinstall_title)
                .setMessage(R.string.agy_dialog_reinstall_msg)
                .setPositiveButton(R.string.agy_dialog_reinstall_btn) { _, _ ->
                    callbacks.onReinstallCliConfirmed()
                }
                .setNegativeButton(R.string.agy_btn_cancel, null)
                .show()
        }

        view.findViewById<View>(R.id.sheet_btn_check_update).setOnClickListener {
            Toast.makeText(activity, activity.getString(R.string.agy_toast_checking_updates), Toast.LENGTH_SHORT).show()
            activity.lifecycleScope.launch {
                val res = AntigravityEngine.checkLatestRelease()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                res.onSuccess { info ->
                    val current = "v${BuildConfig.VERSION_NAME}"
                    if (info.tagName.isNotBlank() && AntigravityEngine.isNewerVersion(info.tagName, current)) {
                        val msg = activity.getString(
                            if (info.isLiteApk) R.string.agy_dialog_update_msg_lite else R.string.agy_dialog_update_msg_full,
                            current,
                            info.tagName
                        )
                        AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.agy_dialog_update_title, info.tagName))
                            .setMessage(msg)
                            .setPositiveButton(R.string.agy_dialog_update_btn) { _, _ ->
                                dialog.dismiss()
                                if (info.downloadApkUrl != null) {
                                    callbacks.onStartInAppUpdate(info)
                                } else {
                                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl))) }
                                }
                            }
                            .setNegativeButton(R.string.agy_btn_later, null)
                            .show()
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.agy_toast_up_to_date, current), Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.agy_toast_update_check_failed, err.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        view.findViewById<View>(R.id.sheet_btn_phantom_fix).setOnClickListener {
            val cmd = "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""
            AlertDialog.Builder(activity)
                .setTitle(R.string.agy_dialog_phantom_title)
                .setMessage(activity.getString(R.string.agy_dialog_phantom_msg, cmd))
                .setPositiveButton(R.string.agy_dialog_phantom_btn) { _, _ ->
                    callbacks.onCopyText("Phantom Process Fix", cmd)
                    Toast.makeText(activity, activity.getString(R.string.agy_toast_phantom_copied), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.agy_btn_close, null)
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
        txtActiveAccount.text = if (active != null) {
            activity.getString(R.string.agy_active_account_email, active.email)
        } else {
            activity.getString(R.string.agy_active_account_none)
        }

        if (accounts.isEmpty()) {
            val emptyTv = TextView(activity).apply {
                text = activity.getString(R.string.agy_accounts_empty)
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
                    acct.isActive -> activity.getString(R.string.agy_account_suffix_active)
                    acct.isInCooldown(now) -> activity.getString(R.string.agy_account_suffix_cooldown)
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
            HubThemeSnapshot.load(activity)?.applyToAccountRow(row, label, acct.isActive)

            if (!acct.isActive) {
                val btnSwitch = TextView(activity).apply {
                    text = activity.getString(R.string.agy_btn_switch)
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
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.agy_toast_account_removed, acct.email),
                                    Toast.LENGTH_SHORT
                                ).show()
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