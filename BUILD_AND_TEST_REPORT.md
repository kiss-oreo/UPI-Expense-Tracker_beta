# Build & Test Report — UPI Expense Tracker 2.1.0-beta.1 (Android 10 test copy)

**Verdict: a real, verified Android 10 test APK was built and published by GitHub Actions.**
It is a **debug test copy** — *not* a production-signed update, and it can never overwrite or update
the app already on your phone.

| | |
|---|---|
| Green run | [run 38613523674](https://github.com/kiss-oreo/UPI-Expense-Tracker_beta/actions/runs/38613523674) |
| Commit | `ce8979b` on `arena/a84acc67-upi-expense-tracker-beta` |
| Artifact | `upi-tracker-2.1.0-beta.1-debug-test-copy` (13,382,516 bytes, 14-day retention) |
| Contains | `app-debug.apk`, `checksums.txt`, `beta-verification.txt` |

---

## 1. First, an important finding about the previous session's work

The 2.1.0 work described as "uncommitted" was **not present and not recoverable**. What was checked:

- `git status` on this checkout was **clean** at `main`'s tip `d9c6e18` — no uncommitted changes at all.
- The repo held exactly one history line: PR #1 (merged), whose tip `04d58bd` still declares
  `versionCode = 10` / `versionName = "2.0.0-beta.1"`.
- No stashes, no dangling/unreachable objects (`git fsck`), no other local checkouts, no other remote
  refs (`git ls-remote`), no artifact or keystore on disk, and **no `BUILD_AND_TEST_REPORT.md`** in any
  commit.

So `versionCode 11` / `2.1.0-beta.1` and the report existed only inside an ephemeral sandbox that has
since been discarded — GitHub never received them. **They were re-created in this session** on top of
the merged 2.0 work. Nothing was reset, cleaned, or deleted; the existing work was left untouched.

What *was* already on the branch and is intact: the beta workflow, the import/export code
(`util/Backup.kt`, `util/BackupCrypto.kt`, `util/CsvExport.kt`, `ui/common/BackupRestorePicker.kt`),
the test suites, the README, and the debug build's "test copy" markings.

## 2. What was built

`versionCode = 11`, `versionName = "2.1.0-beta.1"` (`app/build.gradle.kts`), built by
`.github/workflows/android-beta.yml` on `ubuntu-24.04` with JDK 21 and AGP 9.2.1.

| APK | Package id | versionName | versionCode | minSdk | Internet | Signature |
|---|---|---|---|---|---|---|
| **debug test copy** (the artifact) | `com.goushik.upiwallet.debug` | `2.1.0-beta.1-debug` | 11 | 29 (Android 10) | **absent** | throwaway Android **debug** key |
| release | `com.goushik.upiwallet` | `2.1.0-beta.1` | 11 | 29 | **absent** | unsigned (no `keystore.properties` in CI) |

The release build is produced only to prove the release variant still compiles; it is unsigned, is
**not** a production-signed update, and is not published.

### The debug test copy's measured facts (from the run's own verification)

| Fact | Value |
|---|---|
| File | `app/build/outputs/apk/debug/app-debug.apk` |
| Size | 35,977,676 bytes (34.31 MiB) |
| SHA-256 | `53a8237b7f401002e04223fe5603099b15931f303bbf1cb3ffdc5fc50ee0ea6e` |
| Home-screen label | `UPI ET (test copy)` |
| targetSdk | 36 |

Checksums are **per build**: every run publishes its own `checksums.txt` inside the artifact and in
the run summary. Always compare the APK you downloaded against the `checksums.txt` from the *same*
run — that is exactly what the pipeline does.

## 3. Verification performed (all on GitHub Actions)

| # | Check | Command / mechanism | Result |
|---|---|---|---|
| 1 | Unit tests | `:app:testDebugUnitTest` | **55 test classes, 532 tests, 0 failures, 0 errors, 7 skipped** |
| 2 | Lint | `:app:lint` | **clean** (0 errors) after fixing the three real errors in §4 |
| 3 | Debug build | `:app:assembleDebug` | built |
| 4 | Release build | `:app:assembleRelease` | built |
| 5 | Package ids | `aapt dump badging` | `…upiwallet.debug` (debug) / `…upiwallet` (release) |
| 6 | Versions | `aapt dump badging` | `versionCode 11`, `2.1.0-beta.1-debug` / `2.1.0-beta.1` |
| 7 | Minimum SDK | `aapt dump badging` | `sdkVersion:'29'` — Android 10, on both APKs |
| 8 | No network capability | `aapt dump permissions` | `android.permission.INTERNET` absent from **both** APKs |
| 9 | Signature | `apksigner verify --print-certs` | debug APK verified, signer `CN=Android Debug` (not a production key) |
| 10 | Label | `aapt dump badging` | `UPI ET (test copy)` — distinguishable from the real app everywhere |
| 11 | Size + SHA-256 | script | recorded in the summary, `checksums.txt` and `beta-verification.txt` |
| 12 | Android 10 behaviour | API-29 emulator, `BetaSmokeTest` | **passed** — installs and launches on Android 10, no `INTERNET` in the runtime permission list, and a populated v7 database migrates to v8 without losing a payment or an account |

Checks 5–11 live in [`.github/scripts/verify_beta_apk.py`](.github/scripts/verify_beta_apk.py) and read
the **built binaries**, not the Gradle config, so config and reality cannot drift apart silently. A
single failed check fails the job, and the artifact upload sits behind it — **a bad build is never
published**.

## 4. The three lint errors this gate found (and why they were worth finding)

None of these were lint noise; all three were fixed in the code rather than suppressed:

1. **`NewApi` — a crash on Android 10, the exact platform this beta targets.**
   `LocationSource.requestLive` called `LocationManager.getCurrentLocation(provider, signal, executor,
   Consumer)`, which is **API 30+**. On API 29 that overload does not exist, and the call throws
   `NoSuchMethodError` — an `Error`, so the surrounding `catch (e: Exception)` would never have run:
   tapping *"where am I now"* on the map would have crashed the app. Fixed with a
   `Build.VERSION.SDK_INT >= 30` guard; API 29 now uses the cached fix the map already falls back to.
2. **`MissingPermission` — `LocationSource.freshestLastKnown`** caught only `Exception`. Behaviour was
   already safe (that includes `SecurityException`), but the permission is now handled explicitly, so
   a revoked grant is visibly handled rather than incidentally.
3. **`PermissionImpliesUnsupportedChromeOsHardware` — `RECEIVE_SMS`** implies telephony hardware,
   which silently restricts installs to phones. SMS capture is optional here (manual entry and
   accessibility capture work without it), so the app now declares
   `<uses-feature android:name="android.hardware.telephony" android:required="false" />`.

## 5. Getting the APK

1. Open [run 38613523674](https://github.com/kiss-oreo/UPI-Expense-Tracker_beta/actions/runs/38613523674)
   (or any later green run on this branch).
2. **Artifacts** → `upi-tracker-2.1.0-beta.1-debug-test-copy` → download the zip.
3. Unzip it: `app-debug.apk`, `checksums.txt`, `beta-verification.txt`. Artifacts expire after 14 days.

## 6. Installing it on the phone — safely, side by side

This APK **cannot** overwrite, update or uninstall the app you already have:

- different package id (`com.goushik.upiwallet.debug`), so Android treats it as a separate app;
- signed with a throwaway debug key, so it could not even be offered as an update to the
  developer-signed app;
- it calls itself **UPI ET (test copy)** on the home screen, in Accessibility, on its widgets and in
  the capture-off reminder;
- it keeps its **own, initially empty** database — your real ledger is never touched.

Steps:

1. Optionally verify the download first:
   `sha256sum app-debug.apk` (Linux/macOS) or `Get-FileHash app-debug.apk -Algorithm SHA256`
   (Windows PowerShell) — it must match the line in that run's `checksums.txt`.
2. Copy the APK to the phone and open it; allow installing from that source once.
3. **Do not uninstall your existing UPI Expense Tracker.** Nothing needs to be removed; both apps sit
   side by side.
4. To exercise it with real data: open the test copy → Settings → **Restore a backup** → pick a backup
   file you exported from the real app. It only reads that file.
5. Remove it later by uninstalling **UPI ET (test copy)** only.

The reverse direction is refused by design: the real app will not restore a backup produced by the
test copy, so test data cannot leak into the real ledger.

## 7. Reproducing this locally or in CI

```
./gradlew :app:testDebugUnitTest :app:lint :app:assembleDebug :app:assembleRelease
python3 .github/scripts/verify_beta_apk.py        # after the SDK is present
```

In CI the same sequence runs on every push to `arena/**` or `main`, on PRs to `main`, and can be
started by hand with **Actions → Android beta verification → Run workflow**. The API-29 emulator job
runs only after the APK checks pass.
