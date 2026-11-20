# Dependencies

_Read when: Changing requirements.txt/constraints.txt, Gradle versions, or upgrading a pinned package._

## Dependencies

**`requirements.txt` is intent; `constraints.txt` is resolution.** The
specifiers in `requirements.txt` say what the app needs (`Django>=6.0,<7.0`);
`constraints.txt` pins exactly what that resolves to, and the `Dockerfile`
installs with `-c constraints.txt`. Before this split existed there was no
lockfile of any kind, so nobody could say what was installed — it was whatever
PyPI's latest was at the last `up -d --build` — two rebuilds of one commit
could differ, and a stack of majors (gunicorn 21→26, Pillow 10→12,
django-redis 5→7, qrcode 7→8, timezonefinder 6→9) sat reachable behind
unbounded `>=` specifiers, able to land silently on any rebuild.

**Adding a package means editing both files.** Upgrading means editing a
version in `constraints.txt`, rebuilding, verifying, committing — which is
also the rollback: `git revert` and rebuild. Only **direct** dependencies are
pinned; transitives float, because pinning a full tree needs a real resolver
(pip-tools/uv) and a partial file claiming completeness is worse than one that
states its scope.

**Three dependencies are deliberately not at their newest published version**,
each of which reads as an oversight unless the reason travels with it:

- **`numpy<2.4.0`** — NumPy 2.4.0 made x86-64-v2 (SSE4.2/POPCNT) a mandatory
  runtime baseline **with no fallback**, so it crashes on VPS/hypervisor
  default vCPU models (QEMU's `qemu64`) even on real x86_64 hardware. The cap
  restores the SSE2-baseline wheel with runtime dispatch. Lifting it breaks
  deployment on exactly the virtualised hardware this self-hosts on. (`scipy`,
  pulled in by `reverse-geocode`, accepts `numpy>=2.0,<2.8`, so the cap costs
  nothing there.)
- **`geoip2fast`** — **upstream is abandoned.** 1.2.2 (June 2024) is the final
  release, and the bundled `geoip2fast-ipv6.dat.gz` country database ages with
  it, so the Monitoring country breakdown will slowly misattribute reallocated
  IP ranges. Not an error — a decay, and a known dead end: replacing it means
  picking a different offline country DB, not upgrading this one.
- **MapLibre GL JS stays on v5** (vendored, 5.24.0 — the *final* 5.x, not a
  stale pin). See the Globe entry under Map tools for why v6 is not a bump.
  **The vendored file carries a local patch**: `DOM.removeAttributes` iterates
  `Array.from(e.attributes)` rather than the live `NamedNodeMap`, backporting
  the fix for GHSA-jrc7-96c5-q579 (the attribution sanitizer skipped the
  attribute after each one it removed, so `<details open ontoggle=…>` survived;
  fixed upstream only in 6.4.1). Replacing the file drops the patch — re-apply it.

**Android:** boarding-pass scanning uses ML Kit's **bundled** `barcode-scanning` (model in the APK, ~2–3 MB), deliberately not `play-services-mlkit-barcode-scanning`, which needs Play Services. `kotlin` is pinned to the 2.3 line by **KSP**, which has no 2.4.x
release and which both Hilt and Room run through. `okhttp` stays on 4.x
because 5.x is only reachable together with Retrofit 3, which changes API
surface exactly where `SessionGuardInterceptor` and the `X-Roamly-Client` auth
interceptor sit. `coil` 2.7.0 is **terminal** for that artifact — Coil 3 moved
to `io.coil-kt.coil3` with a package rename, so "updating" means migrating
every `AsyncImage` call site.
