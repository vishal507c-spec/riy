# RIY invariants (first principles)

Har bug jo is repo me mila, in paanch invariants me se kisi ka ullanghan tha.
Code change karte waqt pehle yahan dekho: kaunsa invariant affect hota hai,
uska enforcement/test kahan hai, aur kya dono taraf symmetric hai.

---

## I1 — Secrets shipped artifact me nahi
- Release APK me `GITHUB_TOKEN` nahi: `app/build.gradle.kts` me blank
  `defaultConfig` value, sirf `debug` build type inject karta hai.
- Enforcer: `app/src/test/java/com/vishal/riy/update/BuildConfigTokenTest.kt`.
- Rule: koi bhi credential `BuildConfig`/`strings.xml`/manifest me tabhi jab
  uska scope local debug ho. `UpdateToken.kt` isko document kare.

## I2 — Self-update safe, truthful aur recoverable
- In-place update sirf: `adb install -r` / `PackageInstaller` session. Kabhi
  uninstall ya downgrade nahi.
- Verify order: package → signer SHA-256 → versionCode (strictly newer). Koi
  bhi unknown value fail-closed (reject), fail-open nahi.
- Install result hamesha stable Activity host par deliver hota hai; Fragment
  dismiss hone se callback nahi girta. `BlockedByPolicy` → USB screen.
- Device Owner ka `no_install_unknown_sources` sirf momentarily lift hota hai
  aur **har** completion path par dobara lagaya jata hai.
- Transient refusal sticky nahi: `clearInstallRefusals()` retry aur successful
  install par call hota hai; sirf `prompted_for_install_grant` loop-prevention
  ke liye sticky rehta hai.
- Rule: jo bhi policy lift kare, uska restore `finally` ya equivalent path me ho.

## I3 — Restrictions release symmetry + read-back
- Jo restriction apply hoti hai, uska explicit OFF path hona chahiye.
  `EncryptedDnsPolicy.apply/clear`, `AndroidEnforcementEngine` hardening/
  `restoreNormalPolicyWithResult`.
- Deadline single source of truth: `LockStore` me epoch millis. Expiry par
  `RestrictionExpiryScheduler` system alarm se `ProtectionReconciler` chalata
  hai — UI timer naya deadline nahi banata.
- Persisted session sirf tab clear jab platform restore `APPLIED` ho. Fail par
  session retry ke liye rehta hai.
- API < 26 par `getLockTaskPackages()` nahi hota: `isLockTaskPermitted()` se
  per-package verify, empty read-back ko mismatch mat bolo.
- Rule: state change = write + read-back + verify; unverified state "verified"
  nahi kahla sakta.

## I4 — Filter kabhi blackhole na kare, aur "verified" tab bole jab sach ho
- Failure path tun close kare aur `running=false` kare; restart no-op na ho.
- Self-test me received DNS transaction ID validate ho.
- `RUNNING_UNVERIFIED` ko UI green ACTIVE nahi dikhata — sirf `VERIFIED`.
- Private DNS restriction toggle hatati hai, existing DoT config nahi: status
  reporting me dono alag.
- Rule: har " PROTECTED" claim ke peeche ek real read-back/test proof ho.

## I5 — UI/Docs single source of truth
- `ProtectionUiState.shieldStatus` hi ekmatra shield status hai. Screen khud
  `BlockerState.current()` se parallel status derive na kare.
- Har defined string used ho; har rendered state backend se aaye.
- README/CHANGELOG shipped app ka description ho. v2.9.3 me Status Blocker
  hata diya gaya — docs me uska zikr "removed" ke saath hi ho.
- Rule: comment/docs aur code ek hi kirdaar kahen; jab feature remove ho to
  docs/tests bhi same commit me update.

---

## Enforcement map

| Invariant | Test / guard |
|---|---|
| I1 | `BuildConfigTokenTest` |
| I2 | `ApkCompatibilityTest`, `InstallRouteDeciderTest`, `UpdateInstallerDownloadTest` |
| I3 | `AndroidEnforcementEngineTest`, `DefaultRestrictedModeControllerTest`, `LockSurvivalTest` |
| I4 | `DnsFilterEngineTest`, `ShieldLayersTest` (verified-only ACTIVE) |
| I5 | `ZeroConfusionUiTest`, `StatusBlockerPresentationTest` (removed), docs review |

Nayi bug mile to pehle decide karo wo kis invariant ka violation hai, phir
us invariant ke test map me regression test add karo — ad-hoc fix nahi.
