package pan.alexander.tordnscrypt.tailscale_fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pan.alexander.tordnscrypt.modules.ModulesStatus
import pan.alexander.tordnscrypt.utils.enums.ModuleState.RUNNING
import pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPED
import pan.alexander.tordnscrypt.utils.logger.Logger.logi

private const val BRIDGE_STATUS_ACTION = "io.github.bropines.tailscaled.bridge.STATUS"
private const val EXTRA_RUNNING        = "running"

class TailscaleFragmentReceiver(
    private val view: TailscaleFragmentView,
    private val presenter: TailscaleFragmentPresenter
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (view.getFragmentActivity()?.isFinishing == true) return

        when (intent.action) {
            BRIDGE_STATUS_ACTION -> {
                val running = intent.getBooleanExtra(EXTRA_RUNNING, false)
                // SECURITY (audit #10): same gate as the long-lived receiver in ModulesService.
                // Without it, TailSocks being opened on its own drove this receiver to mark
                // Tailscale RUNNING, which checked the box and force-stopped Tor/I2P.
                // Teardown on stop is handled solely by ModulesService so it runs exactly once.
                if (!TailscaleBridgeStatus.shouldAccept(running)) return
                logi("TailscaleFragmentReceiver: TailSocks status running=$running")
                ModulesStatus.getInstance().tailscaleState = if (running) RUNNING else STOPPED
                presenter.refreshTailscaleState()
            }
            else -> presenter.refreshTailscaleState()
        }
    }
}
