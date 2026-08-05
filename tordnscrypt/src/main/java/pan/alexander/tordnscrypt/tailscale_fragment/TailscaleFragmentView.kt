package pan.alexander.tordnscrypt.tailscale_fragment

import android.app.Activity

interface TailscaleFragmentView {
    fun setTailscaleStatus(resourceText: Int, resourceColor: Int)
    fun setTailscaleStartButtonEnabled(enabled: Boolean)
    fun setTailscaleProgressBarIndeterminate(indeterminate: Boolean)
    fun setStartButtonText(textId: Int)
    fun getFragmentActivity(): Activity?
}
