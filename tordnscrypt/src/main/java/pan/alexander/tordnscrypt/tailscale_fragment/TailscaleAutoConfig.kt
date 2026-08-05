package pan.alexander.tordnscrypt.tailscale_fragment

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import pan.alexander.tordnscrypt.App
import pan.alexander.tordnscrypt.BuildConfig
import pan.alexander.tordnscrypt.modules.ModulesRestarter
import pan.alexander.tordnscrypt.modules.ModulesStatus
import pan.alexander.tordnscrypt.utils.enums.ModuleState.RUNNING
import pan.alexander.tordnscrypt.utils.logger.Logger.loge
import pan.alexander.tordnscrypt.utils.logger.Logger.logw
import pan.alexander.tordnscrypt.utils.preferences.PreferenceKeys.APPS_BYPASS_VPN
import pan.alexander.tordnscrypt.utils.preferences.PreferenceKeys.PROXY_ADDRESS
import pan.alexander.tordnscrypt.utils.preferences.PreferenceKeys.PROXY_PORT
import pan.alexander.tordnscrypt.utils.preferences.PreferenceKeys.USE_PROXY
import java.io.File
import java.util.concurrent.Executors

private val TAILSOCKS_PACKAGE = BuildConfig.TAILSOCKS_PACKAGE
private const val TAILSOCKS_SOCKS_ADDR = "127.0.0.1"
private const val TAILSOCKS_SOCKS_PORT = "48115"
private const val TAILSOCKS_DNS_ADDR = "127.0.0.1:1053"
private const val FORWARDING_RULE = "ts.net $TAILSOCKS_DNS_ADDR"
private const val PREF_PREV_PROXY_ADDR = "tailscale_prev_proxy_addr"
private const val PREF_PREV_PROXY_PORT = "tailscale_prev_proxy_port"
private const val PREF_PREV_USE_PROXY = "tailscale_prev_use_proxy"
private const val PREF_PROXY_APPLIED = "tailscale_proxy_applied"

object TailscaleAutoConfig {

    // Single thread: enable/disable must never interleave (rapid-toggle race).
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TailscaleAutoConfig")
    }

    fun isTailsocksInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(TAILSOCKS_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

    fun onTailscaleEnabled(
        context: Context,
        forwardingRulesPath: String,
        onConfigured: Runnable
    ) {
        executor.submit {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                saveAndApplyProxy(prefs)
                addTailsocksBypassVpn()
                val ruleChanged = addForwardingRule(forwardingRulesPath)

                val modulesStatus = ModulesStatus.getInstance()
                if (ruleChanged && modulesStatus.dnsCryptState == RUNNING) {
                    ModulesRestarter.restartDNSCrypt(context)
                }
                modulesStatus.setIptablesRulesUpdateRequested(context, true)

                onConfigured.run()
            } catch (e: Exception) {
                loge("TailscaleAutoConfig onTailscaleEnabled", e)
            }
        }
    }

    fun onTailscaleDisabled(
        context: Context,
        forwardingRulesPath: String,
        onConfigured: Runnable
    ) {
        executor.submit {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                restoreProxy(prefs)
                removeTailsocksBypassVpn()
                val ruleChanged = removeForwardingRule(forwardingRulesPath)

                val modulesStatus = ModulesStatus.getInstance()
                if (ruleChanged && modulesStatus.dnsCryptState == RUNNING) {
                    ModulesRestarter.restartDNSCrypt(context)
                }
                modulesStatus.setIptablesRulesUpdateRequested(context, true)

                onConfigured.run()
            } catch (e: Exception) {
                loge("TailscaleAutoConfig onTailscaleDisabled", e)
            }
        }
    }

    private fun saveAndApplyProxy(prefs: SharedPreferences) {
        // Re-enable without a disable in between must not overwrite the true
        // pre-Tailscale snapshot with our own injected values.
        if (!prefs.getBoolean(PREF_PROXY_APPLIED, false)) {
            prefs.edit()
                .putString(PREF_PREV_PROXY_ADDR, prefs.getString(PROXY_ADDRESS, TAILSOCKS_SOCKS_ADDR))
                .putString(PREF_PREV_PROXY_PORT, prefs.getString(PROXY_PORT, "1080"))
                .putBoolean(PREF_PREV_USE_PROXY, prefs.getBoolean(USE_PROXY, false))
                .apply()
        }
        prefs.edit()
            .putString(PROXY_ADDRESS, TAILSOCKS_SOCKS_ADDR)
            .putString(PROXY_PORT, TAILSOCKS_SOCKS_PORT)
            .putBoolean(USE_PROXY, true)
            .putBoolean(PREF_PROXY_APPLIED, true)
            .apply()
    }

    private fun restoreProxy(prefs: SharedPreferences) {
        if (!prefs.getBoolean(PREF_PROXY_APPLIED, false)) {
            return
        }
        // Only restore what the user hasn't manually changed since we applied.
        val editor = prefs.edit()
        if (prefs.getString(PROXY_ADDRESS, "") == TAILSOCKS_SOCKS_ADDR
            && prefs.getString(PROXY_PORT, "") == TAILSOCKS_SOCKS_PORT
        ) {
            editor
                .putString(PROXY_ADDRESS, prefs.getString(PREF_PREV_PROXY_ADDR, TAILSOCKS_SOCKS_ADDR))
                .putString(PROXY_PORT, prefs.getString(PREF_PREV_PROXY_PORT, "1080"))
                .putBoolean(USE_PROXY, prefs.getBoolean(PREF_PREV_USE_PROXY, false))
        }
        editor.putBoolean(PREF_PROXY_APPLIED, false).apply()
    }

    private fun addTailsocksBypassVpn() {
        try {
            val preferenceRepository =
                App.instance.daggerComponent.getPreferenceRepository().get()
            val set = preferenceRepository.getStringSetPreference(APPS_BYPASS_VPN)
            if (set.add(TAILSOCKS_PACKAGE)) {
                preferenceRepository.setStringSetPreference(APPS_BYPASS_VPN, set)
            }
        } catch (e: Exception) {
            loge("TailscaleAutoConfig addTailsocksBypassVpn", e)
        }
    }

    // SECURITY (audit HIGH #2/#4, MED #7/#8): the bypass entry added on enable was never removed
    // on disable, so TailSocks stayed permanently excluded from InviZible's tunnel and its own
    // traffic (control-plane, update checks) kept egressing direct after Tailscale was turned off.
    // Remove it UNCONDITIONALLY on disable: a stopped TailSocks bypassing the tunnel is exactly the
    // leak we are closing, so leak-safety wins over preserving a rare manual bypass entry. (An
    // earlier flag-gated version proved unreliable — the flag did not persist, so removal never
    // ran. Verified on-device 2026-07-23: the entry survived disable; unconditional remove fixes it.)
    private fun removeTailsocksBypassVpn() {
        try {
            val preferenceRepository =
                App.instance.daggerComponent.getPreferenceRepository().get()
            val set = preferenceRepository.getStringSetPreference(APPS_BYPASS_VPN)
            if (set.remove(TAILSOCKS_PACKAGE)) {
                preferenceRepository.setStringSetPreference(APPS_BYPASS_VPN, set)
            }
        } catch (e: Exception) {
            loge("TailscaleAutoConfig removeTailsocksBypassVpn", e)
        }
    }

    private fun addForwardingRule(path: String): Boolean {
        try {
            val file = File(path)
            if (!file.exists()) return false
            val content = file.readText()
            if (!content.contains(FORWARDING_RULE)) {
                file.appendText("\n$FORWARDING_RULE\n")
                return true
            }
        } catch (e: Exception) {
            loge("TailscaleAutoConfig addForwardingRule", e)
        }
        return false
    }

    private fun removeForwardingRule(path: String): Boolean {
        try {
            val file = File(path)
            if (!file.exists()) return false
            val lines = file.readLines()
            val filtered = lines.filter { it.trim() != FORWARDING_RULE }
            if (filtered.size != lines.size) {
                file.writeText(filtered.joinToString("\n"))
                return true
            }
        } catch (e: Exception) {
            // Rule left behind means .ts.net queries hit a dead local port after
            // Tailscale is off (resolution failure, not a leak) — make it visible.
            loge("TailscaleAutoConfig FAILED to remove ts.net forwarding rule — " +
                    ".ts.net resolution will fail until DNS rules are cleaned manually", e)
            logw("Check DNSCrypt forwarding rules at $path")
        }
        return false
    }
}
