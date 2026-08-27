package pan.alexander.tordnscrypt.tailscale_fragment

import android.content.Context
import android.widget.Toast
import pan.alexander.tordnscrypt.App
import pan.alexander.tordnscrypt.MainActivity
import pan.alexander.tordnscrypt.R
import pan.alexander.tordnscrypt.modules.ModulesKiller
import pan.alexander.tordnscrypt.modules.ModulesRunner
import pan.alexander.tordnscrypt.modules.ModulesStatus
import pan.alexander.tordnscrypt.settings.PathVars
import pan.alexander.tordnscrypt.utils.enums.ModuleState
import pan.alexander.tordnscrypt.utils.logger.Logger.loge
import javax.inject.Inject
import dagger.Lazy

import pan.alexander.tordnscrypt.utils.enums.ModuleState.*

class TailscaleFragmentPresenter(private var view: TailscaleFragmentView?) {

    @Inject
    lateinit var pathVars: Lazy<PathVars>

    private val modulesStatus = ModulesStatus.getInstance()
    private var fixedModuleState: ModuleState = STOPPED

    init {
        App.instance.daggerComponent.inject(this)
    }

    fun onStart() {
        if (!isActive()) return
        val context = view?.getFragmentActivity() ?: return

        if (TailscaleAutoConfig.isTailsocksInstalled(context)) {
            setButtonEnabled(true)
            when (modulesStatus.tailscaleState) {
                RUNNING              -> { setRunning();  setProgress(false) }
                STARTING, RESTARTING -> { setStarting(); setProgress(true)  }
                STOPPING             -> { setStopping(); setProgress(true)  }
                else                 -> { setStopped();  setProgress(false) }
            }
        } else {
            setButtonEnabled(false)
            view?.setTailscaleStatus(R.string.tvTailscaleNotInstalled, R.color.textModuleStatusColorAlert)
        }
    }

    fun onStop() {
        if (view?.getFragmentActivity()?.isChangingConfigurations == false) {
            fixedModuleState = STOPPED
        }
        view = null
    }

    fun refreshTailscaleState() {
        if (!isActive()) return
        val current = modulesStatus.tailscaleState
        if (current == fixedModuleState && current != STOPPED) return

        when (current) {
            RUNNING              -> { setRunning();  setProgress(false) }
            STARTING, RESTARTING -> { setStarting(); setProgress(true)  }
            STOPPING             -> { setStopping(); setProgress(true)  }
            STOPPED              -> { setStopped();  setProgress(false) }
            else                 -> {}
        }
        fixedModuleState = current
    }

    fun startButtonOnClick() {
        if (!isActive()) return
        val activity = view?.getFragmentActivity() ?: return
        val context: Context = activity

        if (activity is MainActivity && activity.childLockActive) {
            Toast.makeText(activity, activity.getText(R.string.action_mode_dialog_locked), Toast.LENGTH_LONG).show()
            return
        }

        // SECURITY (audit #15): onStart() install-gates the start button, but nothing gated this
        // path — MainFragment.onClick reaches it straight from the checkbox state. A stale
        // EnableTailscale=true with TailSocks since uninstalled therefore still ran
        // onTailscaleEnabled, repointing the proxy at 127.0.0.1:48115 and adding the ts.net DNS
        // forwarding rule, both aimed at a component that no longer exists — leaving the device
        // proxying through a dead SOCKS port until someone reverted it by hand.
        if (modulesStatus.tailscaleState != RUNNING
            && !TailscaleAutoConfig.isTailsocksInstalled(context)
        ) {
            // Fail closed, and clear the stale flag rather than just refusing: Tor and I2P gate
            // their checkboxes on EnableTailscale, so leaving it set strands the user with every
            // module greyed out — the same trap that made clearing it essential for #11.
            try {
                App.instance.daggerComponent.getPreferenceRepository().get()
                    .setBoolPreference(PREF_ENABLE_TAILSCALE, false)
            } catch (e: Exception) {
                loge("TailscaleFragmentPresenter clearing stale EnableTailscale", e)
            }
            revertConfigIfApplied()
            view?.setTailscaleStatus(
                R.string.tvTailscaleNotInstalled,
                R.color.textModuleStatusColorAlert
            )
            setButtonEnabled(false)
            return
        }

        setButtonEnabled(false)
        if (modulesStatus.tailscaleState != RUNNING) {
            setStarting()
            setProgress(true)
            TailscaleAutoConfig.onTailscaleEnabled(
                context,
                pathVars.get().dnsCryptForwardingRulesPath
            ) { ModulesRunner.runTailscale(context) }
        } else {
            setStopping()
            setProgress(true)
            TailscaleAutoConfig.onTailscaleDisabled(
                context,
                pathVars.get().dnsCryptForwardingRulesPath
            ) { ModulesKiller.stopTailscale(context) }
        }
    }

    /**
     * Revert the auto-config when the user unchecks Tailscale while every module is STOPPED
     * (audit #16).
     *
     * MainFragment.onCheckedChanged only routes to [startButtonOnClick] from inside a guard that
     * requires some module to be non-STOPPED. With everything stopped that guard is skipped, so
     * unchecking the box cleared EnableTailscale but left the proxy repointed at 127.0.0.1:48115,
     * the ts.net forwarding rule in place and TailSocks still bypassing the VPN — all aimed at a
     * dead SOCKS port. This is the common case for anyone who toggles the tunnel off to satisfy an
     * app that refuses to run over a VPN.
     *
     * [startButtonOnClick] cannot be reused here: it branches on tailscaleState, which is STOPPED
     * in exactly this situation, so it would take the *enable* path and re-apply the config.
     *
     * No-ops when nothing is applied. Safe if the teardown already ran via the guarded path — the
     * two submissions serialise on TailscaleAutoConfig's single-thread executor and the second
     * finds the applied flag already cleared. Reverting twice is harmless; not reverting is a
     * broken connection, so the bias is deliberate.
     */
    fun revertConfigIfApplied() {
        try {
            val context: Context = view?.getFragmentActivity() ?: return
            if (!TailscaleBridgeStatus.isProxyConfigApplied(context)) return
            TailscaleAutoConfig.onTailscaleDisabled(
                context,
                pathVars.get().dnsCryptForwardingRulesPath
            ) {}
        } catch (e: Exception) {
            loge("TailscaleFragmentPresenter revertConfigIfApplied", e)
        }
    }

    private fun setRunning() {
        view?.setTailscaleStatus(R.string.tvTailscaleRunning, R.color.textModuleStatusColorRunning)
        view?.setStartButtonText(R.string.btnDNSCryptStop)
        setButtonEnabled(true)
    }

    private fun setStarting() {
        view?.setTailscaleStatus(R.string.tvTailscaleStarting, R.color.textModuleStatusColorStarting)
    }

    private fun setStopping() {
        view?.setTailscaleStatus(R.string.tvTailscaleStopping, R.color.textModuleStatusColorStopping)
    }

    fun setStopped() {
        view?.setTailscaleStatus(R.string.tvTailscaleStop, R.color.textModuleStatusColorStopped)
        view?.setStartButtonText(R.string.btnDNSCryptStart)
        setButtonEnabled(true)
    }

    private fun setButtonEnabled(enabled: Boolean) = view?.setTailscaleStartButtonEnabled(enabled)
    private fun setProgress(show: Boolean)          = view?.setTailscaleProgressBarIndeterminate(show)

    fun isActive(): Boolean {
        val activity = view?.getFragmentActivity()
        return activity != null && !activity.isFinishing
    }
}
