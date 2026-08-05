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
