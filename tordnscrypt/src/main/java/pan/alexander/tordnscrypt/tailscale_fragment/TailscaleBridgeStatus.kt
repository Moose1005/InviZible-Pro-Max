package pan.alexander.tordnscrypt.tailscale_fragment

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.preference.PreferenceManager
import pan.alexander.tordnscrypt.App
import pan.alexander.tordnscrypt.BuildConfig
import pan.alexander.tordnscrypt.modules.ModulesStatus
import pan.alexander.tordnscrypt.utils.enums.ModuleState.STARTING
import pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPING
import pan.alexander.tordnscrypt.utils.logger.Logger.loge
import pan.alexander.tordnscrypt.utils.logger.Logger.logw

/** Whether the user turned Tailscale on *in InviZible*. Lives in TorPlusDNSCryptPref. */
const val PREF_ENABLE_TAILSCALE = "EnableTailscale"

/** Set while our proxy/DNS/bypass config is applied. Lives in default SharedPreferences. */
const val PREF_PROXY_APPLIED = "tailscale_proxy_applied"

/** The custom permission both apps declare to gate the bridge. */
private const val BRIDGE_PERMISSION = "pan.alexander.tordnscrypt.BRIDGE_PERMISSION"

/**
 * Internal, app-private signal that ModulesService changed Tailscale state without a broadcast
 * from TailSocks — i.e. a start/stop timeout or a failed start (audit #14).
 *
 * Not part of the bridge: never sent to or accepted from another app, and registered
 * RECEIVER_NOT_EXPORTED.
 */
const val TAILSCALE_STATE_CHANGED_ACTION = "pan.alexander.tordnscrypt.TAILSCALE_STATE_CHANGED"

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
 *  - the bridge peer is verifiably our own co-signed build (audit MEDIUM #12), AND
 *  - we are mid-transition (we sent START/STOP ourselves), or the user has Tailscale enabled
 *    here so tracking TailSocks' liveness is wanted.
 */
object TailscaleBridgeStatus {

    @JvmStatic
    fun shouldAccept(running: Boolean): Boolean {
        // SECURITY (audit #12): everything below assumes the sender is our co-signed TailSocks,
        // an assumption that rested entirely on a self-declared custom permission. Verify it for
        // real before trusting anything about this broadcast.
        if (!isBridgePeerTrusted(App.instance)) {
            return false
        }

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

    /**
     * Whether the bridge is actually protected by what we think protects it (audit MEDIUM #12).
     *
     * The bridge was gated only by the self-declared `BRIDGE_PERMISSION` at
     * `protectionLevel="signature"`, with no in-code check behind it. Android resolves a custom
     * permission to whichever package declared it **first**, so a malicious app installed before
     * InviZible and TailSocks can declare the same name at `protectionLevel="normal"`. Its
     * definition then governs: the signature check is never applied, the attacker is granted the
     * permission, and it can both start/stop TailSocks' exported service and exchange spoofed
     * status broadcasts with us. Neither receiver verified the peer, and the status broadcast is
     * implicit, so nothing else constrained who was on the other end.
     *
     * Two independent checks, either of which is fatal:
     *  1. The *live* definition of the permission must still be signature-level. If a squatter
     *     defined it first as `normal`, this is what catches it.
     *  2. TailSocks must be signed with our key, checked directly rather than inferred from the
     *     permission. This holds even if the permission system is subverted entirely.
     *
     * Fails closed on any exception — a bridge we cannot verify is one we do not use. Not cached:
     * TailSocks can be reinstalled with a different signing key while this process lives, and a
     * cached "trusted" would survive that.
     */
    @JvmStatic
    fun isBridgePeerTrusted(context: Context): Boolean = try {
        val pm = context.packageManager
        val self = context.packageName

        val info = pm.getPermissionInfo(BRIDGE_PERMISSION, 0)
        val level = info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        when {
            level != PermissionInfo.PROTECTION_SIGNATURE -> {
                loge(
                    "Tailscale bridge REFUSED: $BRIDGE_PERMISSION is not signature-level " +
                            "(declared by ${info.packageName}, level=$level). Another app very " +
                            "likely squatted the permission name — refusing to bridge."
                )
                false
            }

            pm.checkSignatures(self, BuildConfig.TAILSOCKS_PACKAGE) !=
                    PackageManager.SIGNATURE_MATCH -> {
                loge(
                    "Tailscale bridge REFUSED: ${BuildConfig.TAILSOCKS_PACKAGE} is not signed " +
                            "with our key — refusing to bridge."
                )
                false
            }

            else -> true
        }
    } catch (e: PackageManager.NameNotFoundException) {
        // TailSocks not installed, or the permission is undefined. Nothing to trust; not an error.
        false
    } catch (e: Exception) {
        loge("TailscaleBridgeStatus isBridgePeerTrusted", e)
        false
    }

    /** True while TailscaleAutoConfig's proxy/DNS/bypass changes are applied and need reverting. */
    @JvmStatic
    fun isProxyConfigApplied(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_PROXY_APPLIED, false)
}
