#!/usr/bin/env python3
"""Gate the 2.1.0-beta.1 test copy: check the BUILT BINARIES against what the build script declares.

This runs in CI after `:app:assembleDebug :app:assembleRelease` and before the APK is published as an
artifact. Everything it reads comes out of the real .apk files (aapt / apksigner), not out of the
Gradle config, so a mismatch between the two is a failure — not a silent pass.

What must hold:

  debug APK      package   com.goushik.upiwallet.debug       (installs ALONGSIDE the real app)
                 version   versionName + "-debug", same versionCode
                 label     marks itself a test copy            (never mistaken for the real icon)
                 signature apksigner verifies it, and the signer is the Android DEBUG key
  release APK    package   com.goushik.upiwallet              (the real app's id, unchanged)
                 version   exactly the declared versionCode/versionName
                 unsigned  expected in CI (no keystore.properties) — reported, not a failure
  both APKs      minSdk    29  = Android 10, the earliest supported release
                 INTERNET  absent from the merged manifest    (the offline guarantee, enforced)
  both APKs      size      reported, plus SHA-256 for the artifact/checksums file

Exit codes: 0 = every check passed (artifact may be uploaded), 2 = at least one check failed.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE_FILE = REPO_ROOT / "app" / "build.gradle.kts"
APK_DIR = REPO_ROOT / "app" / "build" / "outputs" / "apk"

results: list[tuple[bool, str, str]] = []


def check(ok: bool, name: str, detail: str = "") -> bool:
    """Record one check. Prints immediately so a failed run still shows its progress in the log."""
    results.append((ok, name, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else ""), flush=True)
    return ok


def info(name: str, detail: str = "") -> None:
    print(f"INFO  {name}" + (f"  [{detail}]" if detail else ""), flush=True)


def run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, check=False)


def sdk_root() -> pathlib.Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return pathlib.Path(os.environ[var])
    raise SystemExit("Neither ANDROID_HOME nor ANDROID_SDK_ROOT is set.")


def build_tools_tool(name: str) -> str:
    """Locate `aapt`/`apksigner`. Prefer the pinned 36.0.0 the workflow installs, else the newest."""
    tools_dir = sdk_root() / "build-tools"
    versions = sorted(
        (d.name for d in tools_dir.iterdir() if d.is_dir()),
        key=lambda v: [int(p) if p.isdigit() else 0 for p in re.split(r"[.\-]", v)],
        reverse=True,
    ) if tools_dir.is_dir() else []
    ordered = ["36.0.0"] + [v for v in versions if v != "36.0.0"]
    for version in ordered:
        candidate = tools_dir / version / name
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    raise SystemExit(f"Can't find {name} in {tools_dir} or on PATH.")


def find_apk(variant: str, preferred: str) -> pathlib.Path:
    """Resolve an APK from the build output. AGP's exact file name for an unsigned release build has
    changed between versions, so glob the variant directory and prefer the expected name if present."""
    directory = APK_DIR / variant
    if (directory / preferred).is_file():
        return directory / preferred
    candidates = sorted(p for p in directory.glob("*.apk") if p.is_file())
    if candidates:
        return candidates[0]
    return directory / preferred  # does not exist: reported as a failed check by the caller


def declared() -> dict[str, str]:
    """The single source of truth for the expected values: app/build.gradle.kts itself."""
    text = GRADLE_FILE.read_text()

    def grab(pattern: str, label: str) -> str:
        match = re.search(pattern, text, re.MULTILINE)
        if not match:
            raise SystemExit(f"Could not read {label} from {GRADLE_FILE}")
        return match.group(1)

    return {
        "applicationId": grab(r'^\s*applicationId = "([^"]+)"', "applicationId"),
        "versionCode": grab(r"^\s*versionCode = (\d+)", "versionCode"),
        "versionName": grab(r'^\s*versionName = "([^"]+)"', "versionName"),
        "minSdk": grab(r"^\s*minSdk = (\d+)", "minSdk"),
        "debugSuffix": grab(r'^\s*applicationIdSuffix = "([^"]+)"', "applicationIdSuffix"),
        "versionSuffix": grab(r'^\s*versionNameSuffix = "([^"]+)"', "versionNameSuffix"),
    }


def badging(aapt: str, apk: pathlib.Path) -> dict[str, str]:
    out = run(aapt, "dump", "badging", str(apk))
    if out.returncode != 0:
        raise SystemExit(f"aapt dump badging failed for {apk}:\n{out.stderr}")
    text = out.stdout
    package = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text, re.MULTILINE)
    sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'(\d+)'", text, re.MULTILINE)
    label = re.search(r"^application-label:'([^']*)'", text, re.MULTILINE)
    if not (package and sdk):
        raise SystemExit(f"Unexpected aapt badging output for {apk}:\n{text[:2000]}")
    return {
        "package": package.group(1),
        "versionCode": package.group(2),
        "versionName": package.group(3),
        "sdkVersion": sdk.group(1),
        "targetSdkVersion": target.group(1) if target else "?",
        "label": label.group(1) if label else "",
    }


def permissions(aapt: str, apk: pathlib.Path) -> set[str]:
    out = run(aapt, "dump", "permissions", str(apk))
    if out.returncode != 0:
        raise SystemExit(f"aapt dump permissions failed for {apk}:\n{out.stderr}")
    return set(re.findall(r"uses-permission: name='([^']+)'", out.stdout))


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def size_of(path: pathlib.Path) -> str:
    return f"{path.stat().st_size} bytes ({path.stat().st_size / (1024 * 1024):.2f} MiB)"


def verify_common(name: str, apk: pathlib.Path, aapt: str, want: dict[str, str], expected_package: str,
                  expected_version_code: str, expected_version_name: str) -> dict[str, str]:
    """The checks every APK must pass, whatever variant it is."""
    badge = badging(aapt, apk)
    info(f"{name}: {apk.relative_to(REPO_ROOT)}")
    check(badge["package"] == expected_package,
          f"{name}: package id is {expected_package}", f"found {badge['package']}")
    check(badge["versionCode"] == expected_version_code,
          f"{name}: versionCode is {expected_version_code}", f"found {badge['versionCode']}")
    check(badge["versionName"] == expected_version_name,
          f"{name}: versionName is {expected_version_name}", f"found {badge['versionName']}")
    check(badge["sdkVersion"] == want["minSdk"],
          f"{name}: minSdk is {want['minSdk']} (Android 10)", f"found sdkVersion {badge['sdkVersion']}")
    granted = permissions(aapt, apk)
    check("android.permission.INTERNET" not in granted,
          f"{name}: no INTERNET permission in the merged manifest",
          "offline guarantee holds" if "android.permission.INTERNET" not in granted else "INTERNET present")
    check(apk.stat().st_size > 1 * 1024 * 1024,
          f"{name}: file is a real APK, not a stub", size_of(apk))
    info(f"{name}: targetSdk {badge['targetSdkVersion']}, label '{badge['label']}', {size_of(apk)}")
    return badge


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=pathlib.Path, help="write the full verification text here")
    parser.add_argument("--checksums", type=pathlib.Path, help="write sha256sum-style lines here")
    args = parser.parse_args()

    aapt = build_tools_tool("aapt")
    apksigner = build_tools_tool("apksigner")
    want = declared()
    debug_apk = find_apk("debug", "app-debug.apk")
    release_apk = find_apk("release", "app-release-unsigned.apk")
    info("declared in app/build.gradle.kts",
         f"applicationId={want['applicationId']} versionCode={want['versionCode']} "
         f"versionName={want['versionName']} minSdk={want['minSdk']}")
    info("using", f"aapt={aapt}, apksigner={apksigner}")

    for apk in (debug_apk, release_apk):
        check(apk.is_file(), f"built {apk.name}", str(apk))
    if not all(apk.is_file() for apk in (debug_apk, release_apk)):
        return finish(args, debug_apk, release_apk, aapt)

    # ── debug: the side-by-side test copy ───────────────────────────────────────────────────────────
    debug_badge = verify_common(
        "debug test copy",
        debug_apk,
        aapt,
        want,
        expected_package=want["applicationId"] + want["debugSuffix"],
        expected_version_code=want["versionCode"],
        expected_version_name=want["versionName"] + want["versionSuffix"],
    )
    # The debug build must be distinguishable on the home screen AND in Accessibility settings — it sits
    # next to the real app on the same phone. See app/src/debug/res/values/strings.xml.
    check("test" in debug_badge["label"].lower(),
          "debug test copy: label marks it a test copy",
          f"label '{debug_badge['label']}'")
    check(want["debugSuffix"] == ".debug",
          "debug test copy: applicationIdSuffix keeps it side-by-side (.debug)",
          f"suffix {want['debugSuffix']}")

    signed = run(apksigner, "verify", "--print-certs", "-v", str(debug_apk))
    check(signed.returncode == 0, "debug test copy: apksigner verifies the signature",
          "not signed / bad signature" if signed.returncode != 0 else "verified")
    if signed.returncode == 0:
        dn = re.search(r"^Signer #1 certificate DN: (.*)$", signed.stdout, re.MULTILINE)
        digest = re.search(r"^Signer #1 certificate SHA-256 digest: (.*)$", signed.stdout, re.MULTILINE)
        schemes = [s for s in ("v1", "v2", "v3", "v4")
                   if re.search(rf"Verified using {s} scheme.*: true", signed.stdout)]
        signer = dn.group(1).strip() if dn else "?"
        info("debug signer", f"DN: {signer}")
        info("debug signer", f"cert SHA-256: {digest.group(1).strip() if digest else '?'}")
        info("debug signature schemes", ", ".join(schemes) or "?")
        # A test copy must NOT carry a production key: this is what lets the owner keep the real app.
        check("Android Debug" in signer,
              "debug test copy: signed with the throwaway debug key, not a production key",
              f"DN: {signer}")

    # ── release: the real app's identity, unsigned in CI ────────────────────────────────────────────
    verify_common(
        "release",
        release_apk,
        aapt,
        want,
        expected_package=want["applicationId"],
        expected_version_code=want["versionCode"],
        expected_version_name=want["versionName"],
    )
    release_signed = run(apksigner, "verify", "--print-certs", str(release_apk))
    if release_signed.returncode == 0:
        dn = re.search(r"^Signer #1 certificate DN: (.*)$", release_signed.stdout, re.MULTILINE)
        info("release signature", f"signed — DN: {dn.group(1).strip() if dn else '?'}")
    else:
        info("release signature",
             "unsigned — expected in CI: no keystore.properties, so this is not a production-signed "
             "update and must never be installed over the released app")

    return finish(args, debug_apk, release_apk, aapt)


def finish(args: argparse.Namespace, debug_apk: pathlib.Path, release_apk: pathlib.Path, aapt: str | None = None) -> int:
    failed = [f"{name} ({detail})" for ok, name, detail in results if not ok]
    lines = [
        "UPI Expense Tracker beta APK verification",
        "=" * 44,
        *(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else "")
          for ok, name, detail in results),
        "",
        f"{len(results) - len(failed)}/{len(results)} checks passed.",
    ]

    if debug_apk.is_file():
        lines += ["", "debug test copy (the artifact):",
                  f"  file    {debug_apk.relative_to(REPO_ROOT)}",
                  f"  size    {size_of(debug_apk)}",
                  f"  sha256  {sha256(debug_apk)}"]
    if release_apk.is_file():
        lines += ["", "release (unsigned, built to prove the release variant still compiles):",
                  f"  file    {release_apk.relative_to(REPO_ROOT)}",
                  f"  size    {size_of(release_apk)}",
                  f"  sha256  {sha256(release_apk)}"]

    if failed:
        lines += ["", "FAILED CHECKS:"] + [f"  - {f}" for f in failed]
    else:
        lines += ["", "ALL CHECKS PASSED — the debug test-copy APK may be uploaded."]
    report = "\n".join(lines) + "\n"
    print(report, flush=True)

    if args.report and debug_apk.is_file():
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report)
    if args.checksums and debug_apk.is_file():
        args.checksums.parent.mkdir(parents=True, exist_ok=True)
        entries = []
        for label, apk in (("debug test copy", debug_apk), ("release (unsigned)", release_apk)):
            if apk.is_file():
                entries.append(f"{sha256(apk)}  {apk.name}  # {label}, {size_of(apk)}")
        args.checksums.write_text("\n".join(entries) + "\n")

    # Publish the verdict as a check-run annotation as well as the step summary: the annotation is the
    # machine-readable record that survives outside the job log (which on some runners is not fetchable).
    def escape(text: str) -> str:
        return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")

    if failed:
        print(f"::error title=APK checks failed::{escape(' | '.join(failed))}", flush=True)
    elif debug_apk.is_file():
        badge = badging(aapt, debug_apk) if aapt else {}
        print(
            "::notice title=Beta APK verified::"
            + escape(
                f"version={badge.get('versionName')} versionCode={badge.get('versionCode')} "
                f"package={badge.get('package')} minSdk={badge.get('sdkVersion')} internet=absent "
                f"label={badge.get('label')} size={debug_apk.stat().st_size} sha256={sha256(debug_apk)}"
            ),
            flush=True,
        )

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary and debug_apk.is_file():
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write("```\n" + report + "```\n")

    return 2 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
