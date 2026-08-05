# InviZible Pro Max — Native Tailscale Merge Plan

Status: **BETA** (bridge architecture proven on real hardware 2026-07-13; native merge = future direction)

This document scopes moving TailSocks from a *companion app* (current bridge design) into a
*native module inside InviZible itself* — a real fourth tab alongside DNS / Tor / I2P, one app,
one process, one install.

---

## Why merge at all (the real argument, not just "one app is nicer")

The bridge design works, but it forced disabling Android's **"Block connections without VPN"**
(lockdown) kill-switch — because TailSocks is a separate app/UID that InviZible's VPN must
exclude, and lockdown overrides per-app exclusions. That disable weakens the whole device's
leak protection, which fights the original author's security-first ideals.

**A same-process merge removes that entire conflict.** If the Tailscale engine runs inside
InviZible's own UID, there is no second app to exclude, no bypass needed, and lockdown can stay
**ON**. That is the clean, no-leak end state. This is the strongest reason to do it.

---

## Rough mockup — the new tab

```
┌─────────────────────────────────────────────┐
│  ☰   InviZible Pro Max         🔑  🔒  ⋮      │
├─────────────────────────────────────────────┤
│   MAIN     DNS     TOR     I2P    TAILSCALE   │  ← new 5th tab
│  ─────────────────────────────────▔▔▔▔▔▔▔▔▔  │
├─────────────────────────────────────────────┤
│                                               │
│   Status:  Connected                          │  ← matches DNS/Tor/I2P log-tab styling
│   Tailnet: 100.88.45.72                        │
│   Account: user@example.com                   │
│   Exit node: None            [ Select ]       │
│                                               │
│   ┌── Log ──────────────────────────────┐    │
│   │ magicsock: home is now derp-21        │    │  ← same monospace log view as other tabs
│   │ netmap: 3 peers                       │    │
│   │ ipn state: Running                    │    │
│   └───────────────────────────────────────┘    │
│                                               │
│   [ Peers ]   [ MagicDNS ]   [ Netcheck ]     │  ← optional sub-screens, phase 2+
└─────────────────────────────────────────────┘

  First-run (not signed in) state:
   Status: Not connected
   [ Sign in to Tailscale ]   → opens login flow
```

The MAIN tab keeps the existing four checkboxes + the new "Access Tailnet with TAILSCALE" one
(already built). The new dedicated TAILSCALE tab mirrors how DNS/Tor/I2P each have their own tab
with status + scrolling log.

---

## Scope — MVP vs. later

**MVP (what a first native tab needs):**
- Sign-in flow (interactive login URL; auth-key entry optional)
- Start / stop wired to the existing module lifecycle (reuse ModulesStatus.tailscaleState)
- Status line + connection state, styled like the other modules
- A log view (reuse the existing log-tab pattern)
- Peer list (read-only)

**Explicitly NOT in MVP** (TailSocks' full surface — defer):
- Taildrop / Taildrive file sharing
- Serve & Funnel
- Admin API console
- Home-screen widgets, Quick Settings tile
- DPI-bypass (ByeDPI) settings
- Multi-account switching

Porting the connectivity core is the goal. The rest is TailSocks-the-app scope, not what
InviZible needs to route traffic.

---

## DO
- **Reuse the Go core as-is.** `appctr.aar` (already built for all 4 ABIs) is UI-agnostic Go via
  gomobile. It drops into InviZible's `app` module as a dependency exactly as it does in TailSocks.
- **Keep DNSCrypt as the DNS owner.** MagicDNS `.ts.net` still forwards to the local Go DNS
  proxy; don't let two things fight over the device resolver.
- **Match the existing View/Fragment/XML UI patterns** for the tab — do NOT introduce a parallel
  Compose UI stack just for this tab if it can be avoided (see bug risks).
- **Gate everything behind the existing mutual-exclusion rule** (Tailscale on ⇒ Tor/I2P off).
- **Keep lockdown ON once merged** — the whole point. Re-test that TailSocks-core traffic flows
  without any VPN bypass, because it's now the same UID.
- **Version-bump and label clearly as a fork** — respect the GPL and the original author.

## DON'T
- **Don't ship the debug-signed build as a daily driver** (see security note below).
- **Don't try to port all of TailSocks' Compose UI at once** — it's the majority of that app and
  most of it InviZible doesn't need.
- **Don't remove the bridge code yet** — keep it working until the native path is proven; it's
  the fallback and the reference implementation.
- **Don't route DNS through two engines.** One resolver path.
- **Don't assume IPv6 is safe** — default the merged tab to IPv6 disabled until leak-tested.

## Foreseeable bugs / hurdles (write these down before they bite)
1. **Compose vs. Views clash.** InviZible is 100% classic Views/Fragments/XML; TailSocks is 100%
   Jetpack Compose. Options: (a) add `androidx.compose` + a `ComposeView` host to InviZible just
   for this tab, or (b) hand-port the MVP screens to Views. (a) is faster but pulls a big
   dependency and a second UI paradigm into the app; (b) is more work but keeps the codebase
   coherent. Likely start with (a) for MVP, consider (b) for polish.
2. **Gradle / build conflicts.** Two things that were separate apps now share one Gradle module:
   Kotlin stdlib versions, minSdk (InviZible 21 vs TailSocks 24 → merged app must be 24+),
   AndroidX versions, `packaging { jniLibs }` rules, and NDK/ABI filters must all reconcile.
   Expect duplicate-class and version-alignment errors on first assembly.
3. **Native lib collision.** InviZible ships its own `.so` set (libtor, libdnscrypt-proxy, etc.);
   TailSocks ships libtailscale/libgojni/libbyedpi. Merging jniLibs must not clobber or duplicate.
4. **16 KB page-size alignment** (already seen as a warning on Pixel/Android 15) — the Go/native
   libs aren't 16KB-aligned; for a release build this may become a hard requirement, not a warning.
5. **Foreground-service arbitration.** Both currently run their own foreground service +
   notification. Merged, there should be ONE service owning the lifecycle, or Android will
   complain about multiple FGS / notifications.
6. **The VpnService slot.** InviZible owns it. TailSocks' core in userspace mode does NOT need it
   (that's the whole point) — but make sure no TUN-mode code path accidentally calls
   `VpnService.prepare()` from inside InviZible and double-claims the slot.
7. **State/keys storage.** TailSocks stores tailnet node keys in its own app-private dir; merged,
   these move into InviZible's data dir. Migration isn't needed (fresh sign-in), but the storage
   path wiring in the Go core (`TS_STATE`, Taildrop dir env vars) must be repointed.
8. **License/attribution.** Both are open source; merging must preserve both license headers and
   credit bropines (TailSocks) + Asutorufa (original patches) + Tailscale Inc.

---

## Open security items (must close before calling this more than beta)
- [ ] IPv6 leak path to the tailnet via SOCKS5 — test, or hard-disable IPv6 in the merged tab
- [ ] Actual third-party app traffic app → proxy → tailnet peer, end to end (never yet tested)
- [ ] Kill-switch story: once merged, confirm lockdown can stay ON with no loss of function
- [ ] Fail-closed behavior when the engine dies mid-connection (no silent clearnet fallback)
- [ ] Soak / reboot / doze / network-switch stability
