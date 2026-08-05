# UI Integration Contract — InviZible Pro Max

**Purpose:** let a UI redesign (e.g. from Claude Design) be merged into this codebase with minimal
friction. This is the boundary between *look* and *logic*.

**Rule of thumb:** the redesign may change anything visual. It must preserve the **view IDs**,
**widget types**, and **behavioural guarantees** listed below — those are what the Java/Kotlin
binds to at runtime. If an ID disappears or changes type, the app crashes on launch
(`findViewById` returns null → NPE) or silently breaks a security control.

---

## 1. HARD CONTRACT — main_fragment.xml

`MainFragment.java` calls `findViewById` for every ID below. **All must exist, with these exact
types, in whatever layout replaces `main_fragment.xml`.**

### Module toggles (4) — `android.widget.CompoundButton` subclass (CheckBox/Switch/etc.)
| ID | Module | Notes |
|---|---|---|
| `chbHideIpMainFragment` | Tor | |
| `chbProtectDnsMainFragment` | DNSCrypt | **Force-locked ON when Tailscale is on — see §2.1** |
| `chbAccessITPMainFragment` | I2P | |
| `chbTailscaleMainFragment` | Tailscale | our addition |

Code does: `.setChecked()`, `.isChecked()`, **`.setEnabled(false)`** (the lock),
`.setOnCheckedChangeListener(this)`.
→ Any `CompoundButton` subclass works (Switch, MaterialSwitch, Chip w/ checkable...). A custom
view that is NOT a `CompoundButton` will not compile without also editing MainFragment.

### Status text (4) — `android.widget.TextView` subclass
`tvTorMainFragment`, `tvDNSMainFragment`, `tvITPDMainFragment`, `tvTailscaleMainFragment`
Code does: `.setText(int resId)`, `.setTextColor(int)`.

### Progress bars (4) — `android.widget.ProgressBar`
`pbTorMainFragment`, `pbDNSMainFragment`, `pbITPDMainFragment`, `pbTailscaleMainFragment`
Code does: `.setIndeterminate()`, `.isIndeterminate()`, `.setVisibility()`, and **animates
`View.SCALE_X`** via `ObjectAnimator` in `progressBarExpandWithAnimation()` /
`progressBarCollapseWithAnimation()` (MainFragment). Must be a real `ProgressBar`.

### Dividers (4) — `com.google.android.material.divider.MaterialDivider`
`divTorMainFragment`, `divDNSMainFragment`, `divITPDMainFragment`, `divTailscaleMainFragment`
Paired with the progress bars: the animation shows the divider when the bar hides, and vice-versa.
If the redesign drops dividers, they must still exist as (possibly invisible) views, **or**
MainFragment's two animation helpers must be updated in the same PR.

### Start/Stop button — `android.widget.Button` subclass
`btnStartMainFragment`
Code does: `.setText()`, `.setBackground()`, `.setOnClickListener(this)`.

### Log views (existing modules only — not Tailscale)
`svDNSCryptLog`, `svTorLog`, `svITPDLog` (`ScrollView`), `tvDNSCryptLog`, `tvTorLog`,
`tvITPDLog`, `tvITPDinfoLog` (`TextView`), `clITPDLog` (`ConstraintLayout`).
Note the lowercase `i` in **`tvITPDinfoLog`** — it is inconsistent with its siblings; don't
"fix" it without updating MainFragment.
Bound in the per-module tab fragments, not the main card. If the redesign restructures tabs, these
must survive.

---

## 2. BEHAVIOURAL CONTRACT — do not break these

These are **security controls**, not cosmetics. Each was fixed in response to an audit finding
(see `test-evidence/AUDIT_FINDINGS.md`) and verified on-device.

### 2.1 DNSCrypt lock (audit CRITICAL #1)
When Tailscale is ON, DNSCrypt is **forced checked and disabled**. DNSCrypt owns the capturing
VpnService that routes proxied traffic to TailSocks; if it were off while Tailscale ran, app
traffic would egress **clearnet**. The disabled checkbox is the visible half of a real leak fix.

→ A redesign may restyle the locked state (greying, a lock glyph, a tooltip — an explanatory
affordance would genuinely be an improvement). It **must not** make the control operable while
Tailscale is on.

### 2.2 Mutual exclusion (audit HIGH #5/#6)
Tailscale ON ⇒ Tor and I2P are unchecked **and disabled**. Toggling Tailscale off re-enables them
(it does not auto-start them — user's choice).
The lock is enforced in `setTorStartButtonEnabled()` / `setITPDStartButtonEnabled()`, which gate
on the `EnableTailscale` pref so the lock survives rotation/backgrounding. Don't route enable/
disable around those methods.

### 2.3 Four independent status lines
Each module reports its own state (Stopped / Starting / Running / Stopping, plus Tailscale's
"TailSocks Not Installed"). Collapsing them into one aggregate indicator would hide the state the
security model depends on — e.g. "Tailscale Running while DNSCrypt Stopped" must remain visibly
distinguishable, since that combination is exactly what §2.1 prevents.

### 2.4 Install-gating
When TailSocks isn't installed, the Tailscale row shows `tvTailscaleNotInstalled` and the toggle
is disabled. Keep a visibly distinct "unavailable" state from "available but off".

---

## 3. STRINGS

Ours (already in `res/values/strings.xml`):
```
tvTailscaleStop           "Tailscale Stopped"
tvTailscaleStarting       "Tailscale Starting"
tvTailscaleRunning        "Tailscale Running"
tvTailscaleStopping       "Tailscale Stopping"
tvTailscaleNotInstalled   "TailSocks Not Installed"
main_fragment_tailscale   "Access Tailnet with TAILSCALE"
```
Wording may change freely (these are user-facing). **Key names must not** — they're referenced as
`R.string.*` from `TailscaleFragmentPresenter.kt` and `main_fragment.xml`.

Upstream InviZible is localised (Crowdin). New/renamed strings land English-only until translated;
prefer reusing existing keys where the meaning matches.

---

## 4. SAFE TO CHANGE FREELY

Colors, dimens, typography, spacing, corner radius, elevation, backgrounds, icons, dark/light
theming, card grouping and ordering, animations (except the ProgressBar `SCALE_X` pairing in §1),
layout containers, and any **additional** views. Adding views is always safe; removing or
retyping the ones in §1 is not.

---

## 5. MERGE PROCEDURE (recommended)

1. Land the redesign on a branch (`ui-redesign`), not directly on the working tree — our
   uncommitted bridge work lives there.
2. Diff `main_fragment.xml` before/after and check every §1 ID still resolves:
   ```bash
   # from repo root — should print nothing missing
   for id in $(grep -oE "R\.id\.[a-zA-Z_]+" \
       tordnscrypt/src/main/java/pan/alexander/tordnscrypt/main_fragment/MainFragment.java \
       | sed 's/R\.id\.//' | sort -u); do
     grep -q "@+id/$id" tordnscrypt/src/main/res/layout/*.xml || echo "MISSING: $id"
   done
   ```
3. Build: `.\gradlew.bat :tordnscrypt:assembleFdroidX86_64Debug --console=plain --no-watch-fs`
4. **Re-run the four §2 behaviour checks on-device** — a layout change can silently break the
   DNSCrypt lock (e.g. if a new layout re-enables a checkbox on inflate). Procedure and expected
   evidence: `test-evidence/RUNTIME_TEST_LOG.md`.
5. Only then merge to the main working branch.

---

## 6. CONTEXT FOR THE DESIGNER

- Upstream InviZible is a **GPL-3.0** privacy app by Garmatin Oleksandr (`Gedsh/InviZible`); this
  is a fork. Keep attribution intact wherever it appears (About screen, etc.).
- Existing app style is utilitarian: cards, monospace-ish headers, high-contrast status colors
  (green running / grey stopped / blue starting / red alert). Prior direction from the project
  owner was to match that footprint closely; that constraint can now relax if the redesign is
  applied consistently across all four modules rather than only the new one.
- The Tailscale row is a **fourth peer** to DNS/Tor/I2P, not a special case — treat it visually as
  an equal member of the set.
- Genuine UX gaps worth solving in a redesign (currently unaddressed): explaining *why* a control
  is locked (§2.1/§2.2 give no on-screen reason today), and surfacing the "TailSocks not
  installed" state with a route to install it.
