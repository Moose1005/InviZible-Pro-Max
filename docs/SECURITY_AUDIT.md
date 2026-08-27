# Security audit — Tailscale bridge

Adding Tailscale as a fourth module meant adding a cross-app IPC channel, a proxy that gets
repointed at runtime, and a mutual-exclusion rule between modules. All three are places where a
privacy app can quietly stop being private, so the bridge was audited line by line rather than
just tested for "does it work".

This document tracks all 21 findings from that audit and, just as importantly, **how far each one
has actually been verified**. A fix that compiles is not a fix that works.

## How verification is graded

| Grade | Meaning |
|---|---|
| **Device-verified** | Reproduced the bug on a device, applied the fix, re-ran the same scenario, captured before/after evidence |
| **Reached** | Proved on-device that the new code path executes, but the end effect could not be exercised |
| **Code-complete** | Written and compiling. Not run. |
| **By inspection** | Every code path traced by hand and found sound, but never executed as a test |

---

## Findings

| # | Severity | Issue | Status |
|---|---|---|---|
| 1 | CRITICAL | Tailscale on with DNSCrypt off meant **apps egressed clearnet** while the UI read "Tailscale Running". DNSCrypt owns the service that captures app traffic. | Device-verified |
| 2, 4 | HIGH | The VPN bypass entry added on enable was never removed on disable, so TailSocks kept egressing outside the tunnel after Tailscale was switched off. | Device-verified |
| 3 | HIGH | TailSocks' TUN mode could seize the single system `VpnService` slot InviZible owns, revoking its tunnel. Bridge starts now force SOCKS-only — including against an already-running instance. | Reached — see note below |
| 5 | HIGH | Mutual exclusion called `startButtonOnClick`, which toggles on state, so a module caught mid-`STARTING` was **re-launched instead of stopped**. Replaced with a state-agnostic `forceStop`. | By inspection |
| 6 | HIGH | The exclusion lock was not durable: Tor/I2P presenters re-enabled their own checkboxes on every fragment start, so a screen rotation let the user start Tor *alongside* Tailscale. Both setters now gate on the enable flag. | By inspection |
| 7, 8 | MEDIUM | Further instances of the same bypass-removal leak as #2/#4. | Device-verified |
| 9 | MEDIUM | A failed or timed-out start left the full config applied against a dead SOCKS port. | Partial — helper device-verified via #11; the two new triggers are code-complete |
| 10 | MEDIUM | Any `running=true` was trusted. TailSocks starting from its own launcher icon, tile, widget or boot receiver drove InviZible to RUNNING, which **silently force-stopped the user's Tor and I2P** — dropping anonymity they believed was active, without them touching the app. | Device-verified |
| 11 | MEDIUM | TailSocks stopping outside InviZible left proxy, DNS rule and bypass applied against a dead port. | Device-verified |
| 12 | MEDIUM | The bridge trusted a permission it never verified. Android resolves a custom permission to whichever package declared it **first**, so an app installed before ours could declare it `normal` and be granted it. Now checks the live definition is still signature-level *and* verifies the peer's signature directly. | Code-complete |
| 13 | MEDIUM | Two code paths rewrote the DNSCrypt forwarding-rules file from different threads with no lock, so the `ts.net` rule could be silently erased — sending tailnet hostnames to a public resolver. Shared lock, plus atomic writes because dnscrypt-proxy reads the file as a separate native process and cannot take our lock. | Code-complete |
| 14 | MEDIUM | A timed-out start left the UI stuck: status text on "Starting", spinner running, until the fragment was recreated. | Code-complete |
| 15 | MEDIUM | A stale enable flag with TailSocks uninstalled still repointed the proxy at a dead port. Now refuses *and* clears the flag — refusing alone strands the user with every module greyed out. | Code-complete |
| 16 | LOW | Switching Tailscale off while every module was stopped skipped the teardown entirely and orphaned the whole config. Filed LOW; in practice the one that hit real daily use hardest. | Device-verified |
| 17 | LOW | The `ts.net` DNS rule only reloaded when DNSCrypt was exactly `RUNNING`, which missed the ordinary "press Start with both checked" case. | Code-complete |
| 18 | LOW | Timeout runnables were never cancelled, so a stale timeout could abort an unrelated later operation. | Code-complete |
| 19 | LOW | *TailSocks side.* `running=true` was broadcast before the tunnel existed. Now sent only after the daemon readiness checkpoint passes. | Code-complete |
| 20 | LOW | A start timeout declared STOPPED without stopping the service it had launched, leaving the tunnel up and routing while the UI said otherwise. | Code-complete |
| 21 | LOW | `setChecked(false)` inside the exclusion path re-entered the listener and called `startButtonOnClick` — not the `forceStop` that path uses deliberately — partially re-introducing #5. | Code-complete |

---

## Note on #3

Verifying #3 end to end requires TUN mode to actually function. For a long time it did not in any
build produced here: `libhev-socks5-tunnel.so` was absent from every ABI, so
`TunVpnService.nativeLoaded` was false and both `startTunMode()` and `stopTunMode()` returned
immediately.

The cause turned out to be an uninitialised git submodule in the TailSocks repository. The Gradle
build only wires up `ndkBuild` when the prebuilt library is missing, and `ndkBuild` needs those
submodule sources — with an empty directory it produced nothing and reported no error.

Two consequences worth stating plainly:

1. It explains a **false positive** seen during testing, where TailSocks displayed "Active + TUN"
   while no tunnel existed. The label reflected the setting, not a running tunnel.
2. While TUN could not load, #3's risk was **latent rather than active** in these builds — TailSocks
   cannot seize a VPN slot through a tunnel it cannot start. The risk is real against any build
   that does ship the library.

On-device logging confirmed the new SOCKS-only call site executes on the already-running path — a
log line that could not appear before the fix, because that method was never called there. The end
effect remains unverified until a build with a working TUN library is tested.

---

## What this list is not

Nine of these fixes are code-complete and have never been run on a device. That is recorded here
rather than smoothed over, because on this codebase specifically, two things that looked correct on
the surface turned out not to be: the "Active + TUN" display above, and finding #6, which was
carried as open for a full session because it was read off a stale audit file instead of the code.

Anything not marked **Device-verified** should be treated as untested.
