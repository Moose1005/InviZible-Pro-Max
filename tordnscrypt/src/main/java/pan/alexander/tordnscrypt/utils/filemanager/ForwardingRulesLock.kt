package pan.alexander.tordnscrypt.utils.filemanager

/**
 * Shared monitor for the DNSCrypt forwarding-rules files (audit #13).
 *
 * Two unrelated code paths rewrite `dnsCryptForwardingRulesPath` from different threads:
 * [pan.alexander.tordnscrypt.tailscale_fragment.TailscaleAutoConfig] appends and removes the
 * `ts.net 127.0.0.1:1053` line on its own single-thread executor, while
 * [pan.alexander.tordnscrypt.settings.tor_preferences.ModifyForwardingRules] snapshots the file,
 * writes a temp copy and copies it back. Neither took a lock, so an interleaving where
 * ModifyForwardingRules snapshots, Tailscale appends, then ModifyForwardingRules copies its stale
 * snapshot back would silently drop the ts.net rule while state still reported it as configured.
 *
 * The consequence is not merely a failed lookup: `.ts.net` queries would go to DNSCrypt's public
 * upstream instead of TailSocks' MagicDNS, exposing private tailnet device hostnames to a
 * third-party resolver.
 *
 * Every read-modify-write of those files must hold [LOCK]. Note this only orders *our own*
 * writers against each other — dnscrypt-proxy reads the file as a separate native process and
 * cannot participate, which is why writers must also replace the file atomically rather than
 * truncating it in place.
 */
object ForwardingRulesLock {
    @JvmField
    val LOCK = Any()
}
