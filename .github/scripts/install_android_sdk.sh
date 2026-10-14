#!/usr/bin/env bash
# Install Android SDK packages, retrying a failed download.
#
# A run once died here with "An error occurred while preparing SDK package Android SDK Platform 29:
# Error reading Zip content from a SeekableByteChannel" — a truncated download of platform-29_r05.zip,
# nothing to do with the code under test. The half-unpacked package is left behind and sdkmanager keeps
# failing on it, so each retry clears that package's directory first.
#
# Usage: install_android_sdk.sh 'platforms;android-29' 'build-tools;36.0.0' ...
set -uo pipefail

packages=("$@")
if [ "${#packages[@]}" -eq 0 ]; then
  echo "usage: $0 <sdk-package> [<sdk-package> ...]" >&2
  exit 2
fi

log="${SDK_INSTALL_LOG:-sdk-install.log}"
echo "Android SDK: ${ANDROID_HOME:-unset}; sdkmanager: $(command -v sdkmanager || echo absent)"
yes | sdkmanager --licenses > /dev/null 2>&1 || true

drop_partial_package() {
  local pkg="$1" name="${1#*;}"
  case "$pkg" in
    platforms\;*)     rm -rf "${ANDROID_HOME:?}/platforms/$name" ;;
    build-tools\;*)   rm -rf "${ANDROID_HOME:?}/build-tools/$name" ;;
    system-images\;*) rm -rf "${ANDROID_HOME:?}/system-images/${name//;/\/}" ;;
  esac
}

for attempt in 1 2 3; do
  if sdkmanager --install "${packages[@]}" > "$log" 2>&1; then
    echo "SDK ready (attempt $attempt): ${packages[*]}"
    if grep -qi "error\|warning: " "$log"; then
      echo "sdkmanager notes (non-fatal):"
      grep -i "error\|warning: " "$log" | head -5
    fi
    exit 0
  fi
  echo "::warning title=SDK install retry::attempt $attempt of 3 failed for: ${packages[*]}"
  if [ "$attempt" -eq 3 ]; then
    echo "::error title=SDK setup::$(tail -n 45 "$log" | python3 -c 'import sys; print(sys.stdin.read().replace("%","%25").replace("\n","%0A").replace("\r","%0D"))')"
    exit 1
  fi
  for pkg in "${packages[@]}"; do drop_partial_package "$pkg"; done
  rm -rf "$ANDROID_HOME/temp" "$ANDROID_HOME/.temp" 2>/dev/null || true
  sleep 10
done
