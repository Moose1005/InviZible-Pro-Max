package pan.alexander.tordnscrypt.tailscale_fragment

import android.content.Context
import androidx.preference.PreferenceManager
import pan.alexander.tordnscrypt.App
import pan.alexander.tordnscrypt.modules.ModulesStatus
import pan.alexander.tordnscrypt.utils.enums.ModuleState.STARTING
import pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPING
import pan.alexander.tordnscrypt.utils.logger.Logger.loge
import pan.alexander.tordnscrypt.utils.logger.Logger.logw

/** Whether the user turned Tailscale on *in InviZible*. Lives in TorPlusDNSCryptPref. */
const val PREF_ENABLE_TAILSCALE = "EnableTailscale"

/** Set while our proxy/DNS/bypass config is applied. Lives in default SharedPreferences. */
const val PREF_PROXY_APPLIED = "tailscale_proxy_applied"

/**
 * Shared gate for TailSocks' bridge STATUS broadcasts.
 *
 * SECURITY (audit MEDIUM #10/#11): both bridge receivers used to act on ANY status broadcast.
 * TailSocks emits `running=true` on every start — including ones InviZible never asked for
 * (its own launcher icon, the quick-settings tile, a widget, or its BOOT_COMPLETED receiver).
 * An unsolicited `running=true` therefore drove InviZible's internal state to RUNNING, which
 * propagated to the checkbox, fired `enforceTailscaleMutualExclusion(true)`, and **silently
 * stopped the user's running Tor and I2P** — dropping anonymity the user believed was active,
 * without them touching InviZible at all.
 *
 * Broadcasts are only acted on when InviZible has a legitimate reason to expect one:
 *  - we are mid-transition (we sent START/STOP ourselves), or
 *  - the user has Tailscale enabled here, so tracking TailSocks' liveness is wanted.
 *
 * Note the signature-level BRIDGE_PERMISSION already blocks third-party senders; this guards
 * against confusing *our own* co-signed app's independent lifecycle with a user request.
 */
object TailscaleBridgeStatus {

    @JvmStatic
    fun shouldAccept(running: Boolean): Boolean {
        val state = ModulesStatus.getInstance().tailscaleState
        // A transition InviZible initiated — this is the reply we are waiting for.
        if (state == STARTING || state == STOPPING) return true

        val enabledByUser = try {
            App.instance.daggerComponent.getPreferenceRepository().get()
                .getBoolPreference(PREF_ENABLE_TAILSCALE)
        } catch (e: Exception) {
            loge("TailscaleBridgeStatus shouldAccept", e)
            false
        }

        if (!enabledByUser) {
            logw(
                "Tailscale bridge: ignoring unsolicited status running=$running " +
                        "(Tailscale is not enabled in InviZible — TailSocks was started elsewhere)"
            )
        }
        return enabledByUser
    }

    /** True while TailscaleAutoConfig's proxy/DNS/bypass changes are applied and need reverting. */
    @JvmStatic
    fun isProxyConfigApplied(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_PROXY_APPLIED, false)
}
