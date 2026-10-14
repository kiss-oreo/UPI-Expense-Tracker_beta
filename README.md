# UPI Expense Tracker

*A wallet you glance at.*

<p>
  <a href="https://f-droid.org/packages/com.goushik.upiwallet/"><img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80"></a>
  <a href="https://upiet.in"><img src=".github/assets/website-badge.png" alt="Visit the website: upiet.in" height="80"></a>
</p>

Get it on **F-Droid**: it builds the app itself from this source code and publishes it only if its
build matches the signed APK in [Releases](../../releases). For more details and the install guide,
visit **[upiet.in](https://upiet.in)**.

An Android-first, **100% offline** personal expense tracker for UPI payments. It quietly captures
every payment you make, sorts it, and shows your daily / weekly / monthly spend plus an
at-a-glance available balance — on the home screen and in home-screen widgets. No account, no
cloud, no ads, no tracking. Everything stays on your phone.

> **Sideload-only.** This app reads your payment screens and bank SMS to work, which is not
> permitted on the Google Play Store — so it is distributed only through open-source channels
> (see [Install](#install)). It is a single-user tool you install for yourself.

---

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="19%" alt="Home — spend this month, today/this week, recent payments" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="19%" alt="Insights — spend by category" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="19%" alt="Insights — spend chart" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="19%" alt="Home-screen widgets" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="19%" alt="Settings — capture health and wallet" />
</p>

## What it does

- **Captures payments automatically.** An Accessibility Service reads the on-screen confirmation
  from your UPI app (Google Pay, PhonePe, Paytm, CRED) and your bank's payment SMS, then records
  the amount, payee, and time. Duplicates are removed by the bank reference number.
- **Shows your spend.** This-week / this-month totals, a spend chart, recent transactions, and
  automatic categories (Food, Groceries, Transport, Bills, and so on).
- **Available-balance wallet.** A manual baseline minus your captured spends; re-anchor to the
  real figure any time.
- **Home-screen widgets.** Spend and available-balance widgets, a monthly-budget widget, and a
  week/month budget-progress widget. If capture is switched off, every widget says so.
- **Budgets.** Set a monthly cap and watch it fill on the home card and a widget.
- **Backups and export, both offline.** Write a password-encrypted backup file (or a plain CSV) wherever
  you choose through the system file picker, and restore it later — including after an uninstall.
  Restoring merges, so replaying a backup never double-counts a payment.

## Privacy — nothing leaves your phone

- The **`INTERNET` permission is actively stripped** from the app, so it is *incapable* of sending
  data off the device. This is enforced in the manifest, not just a promise.
- **No cloud, no analytics, no crash reporting, no ads, no third-party tracking.** Data lives in a
  local database only.
- **Export is local and manual** — you can export a CSV and share it yourself; the app never
  uploads anything.
- **Backups go only where you put them.** The encrypted backup is AES-256-GCM under a password you
  choose (PBKDF2-HMAC-SHA256, 240,000 iterations), written through the system file picker; the app
  keeps no copy of the password and cannot recover it. Blanket plain-text auto-backup is OFF unless
  you turn it on, and when it is on the file is unencrypted and the app says so.
- The donate button simply opens a payment page in your browser; there is no payment SDK in the app.

## Permissions, and why

| Permission | Why | Optional? |
|---|---|---|
| Accessibility Service | Read the UPI app's payment-confirmation screen to capture a payment | Required for auto-capture; you can also add payments manually |
| `RECEIVE_SMS` | Receive incoming bank payment SMS (reference number, credits) — the delivery broadcast only, not your inbox | Required for SMS capture |
| `ACCESS_FINE/COARSE_LOCATION` | Pin *where* a payment happened, for the optional Insights map | Opt-in; no background location |
| `POST_NOTIFICATIONS` | A reminder when payment capture is switched off, plus optional budget nudges | Asked once |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep the capture service alive in the background | Opt-in |

## Install

- **Direct / Obtainium** — grab the signed APK from [Releases](../../releases), or add this repo in
  [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates.
- **F-Droid** — [f-droid.org/packages/com.goushik.upiwallet](https://f-droid.org/packages/com.goushik.upiwallet/).
  F-Droid rebuilds it from this source and ships the same developer-signed APK, so you can switch
  between F-Droid and the Releases APK without losing data.

After installing, enable the Accessibility capture service and grant SMS access when prompted. A
**Force stop** in App info, or a phone cleaner that closes apps, switches capture off; open the app
once afterwards and it tells you what to turn back on.

### Trying the 2.1.0-beta.1 test copy (side-by-side, your data untouched)

Every push to a beta branch builds a **debug test copy** and publishes it on the run's page:
**Actions → Android beta verification → the run → Artifacts → `upi-tracker-2.1.0-beta.1-debug-test-copy`**.
The artifact contains `app-debug.apk`, its `checksums.txt`, and the full verification report.

That copy is built to be **installed next to the real app, never over it**:

- its own package id (`com.goushik.upiwallet.debug`), so Android treats it as a different app — it
  cannot replace, update, or uninstall your installed UPI Expense Tracker;
- it calls itself **UPI ET (test copy)** on the home screen, in Accessibility, on its widgets and in
  the capture-off reminder, so you always know which one you are looking at;
- its **own database**, starting empty. Nothing is shared with the real app's ledger;
- signed with a **throwaway debug key**, not the developer's release key — which is exactly what lets
  it coexist with the real app. It is not a production-signed update and will never update that app.

To try it:

1. On the run page, download the artifact zip and unzip it to get `app-debug.apk`.
2. Check the download if you like: `sha256sum app-debug.apk` (Linux/macOS) or
   `Get-FileHash app-debug.apk -Algorithm SHA256` (Windows). It must match the `sha256` line in the
   run's summary and in `checksums.txt`.
3. Copy the APK to the phone and open it. Android asks once to allow installing from that app/source.
   Nothing is uninstalled — and **do not** uninstall your existing app to make room.
4. To test against real data, open the test copy → Settings → **Restore a backup** and pick a backup
   you exported from the real app. It only reads that file; your real app is untouched.
5. Remove it any time by uninstalling **UPI ET (test copy)** only.

The reverse direction is refused on purpose: the real app will *not* restore a file the test copy
produced, so test-copy rows can never leak into your real ledger. Requires Android 10 (API 29) or
newer; CI artifacts expire after 14 days.

## Backups and export

Under **Settings → Data**:

- **Encrypted backup** — choose a password (8+ characters) and save the file wherever you like: a
  drive of your own, a cloud folder you chose, a USB stick. Only that password opens it and there is
  no recovery, so keep it somewhere safe.
- **Restore a backup** — merges the file into whatever is on the phone. Payments already present are
  skipped ("already on this phone: N"), the phone's own name and UPI IDs win, and nothing is deleted.
  A file from a newer app version is refused rather than half-read.
- **Export to CSV** — the payments you see, with plain rupee numbers, sortable ISO dates, and a column
  saying whether each row counts as spend. Share straight to Sheets / Drive / Gmail.
- **Legacy unencrypted backup** (off by default) — plain JSON kept working for the 1.x flow. It holds
  raw captured screens and SMS text, so the app warns before writing one.

A backup is the only thing that survives uninstalling the app: Android erases the private database,
and Google Auto Backup is off.

## Build it yourself

Requires **JDK 21** and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

CI runs and verifies the same thing on every push: [`.github/workflows/android-beta.yml`](.github/workflows/android-beta.yml)
runs the unit tests, lint, `:app:assembleDebug` and `:app:assembleRelease`, then
[`.github/scripts/verify_beta_apk.py`](.github/scripts/verify_beta_apk.py) inspects the built binaries
— package ids, versionCode/versionName, minSdk 29, the absence of `INTERNET`, the debug-key signature,
size and SHA-256 — and only then publishes the debug APK as an artifact. A separate job re-checks
installation, launch, the missing `INTERNET` permission and a real v7→v8 database migration on an
Android 10 (API 29) emulator.

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · WorkManager · AccessibilityService + SMS receiver ·
[Haze](https://github.com/chrisbanes/haze) for the glass UI. All dependencies are free and
open-source. No proprietary Google Play Services, Firebase, or networking libraries.

## Legal (one line — not legal advice)

A single-user, sideloaded app that processes *your own* SMS and notifications on *your own* device
falls under the personal-use exemption of India's DPDP Act §3(c)(i); it only reads
completed-payment artifacts and holds no RBI-regulated role.

## Contact

Built by Goushik. Questions, feedback, or security reports: **upiexpensetracker@gmail.com** — or [open an issue](../../issues).

## License

[GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`).
Copyright © 2026 Goushik. You may use, study, share, and modify this software; any distributed
derivative must also remain open-source under the GPL.
