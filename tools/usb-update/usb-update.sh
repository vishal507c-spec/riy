#!/usr/bin/env bash
# Safe USB / ADB update helper for the Riy Android app (macOS / Linux).
#
# Mirrors usb-update.ps1 exactly, with the same safety rules:
#   * the target package is a hard constant; nothing else is ever installed
#   * the APK's package name is verified before install
#   * a signing-certificate mismatch is reported, never worked around
#   * `install -r` is always used, so app data is preserved
#   * no arbitrary shell is executed; all external tools get explicit argv
#
# Usage:
#   ./usb-update.sh                 # latest GitHub release
#   ./usb-update.sh 2.7.0           # a specific version
#   ./usb-update.sh --apk ./riy-v2.7.0.apk
#   ./usb-update.sh 2.7.0 --pull    # pull the APK off the phone

set -euo pipefail

EXPECTED_PACKAGE='com.vishal.riy'
OWNER='vishal507c-spec'
REPO='riy'

VERSION=""
APK_PATH=""
PULL=0

while [ $# -gt 0 ]; do
  case "$1" in
  --apk)  APK_PATH="$2"; shift 2 ;;
  --pull) PULL=1; shift ;;
  -*)     echo "Unknown option: $1" >&2; exit 2 ;;
  *)      VERSION="$1"; shift ;;
esac
done

# GitHub tags are "vX.Y.Z". Accept either spelling from the user.
case "$VERSION" in
  ""|v*) ;;
  *) VERSION="v$VERSION" ;;
esac

say()  { printf '\033[36m==> %s\033[0m\n' "$1"; }
ok()   { printf '    \033[32mOK  %s\033[0m\n' "$1"; }
warn() { printf '    \033[33m!!  %s\033[0m\n' "$1"; }
die()  { printf '\033[31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

# Fixed GitHub API calls. A read-only token is used only when the release
# repository is private. It comes from the environment and is never written,
# echoed, or logged.
github_api_get() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -H 'User-Agent: riy-usb-update' "$1"
  else
    curl -fsSL -H 'User-Agent: riy-usb-update' "$1"
  fi
}

github_download() {
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" -o "$2" "$1"
  else
    curl -fsSL -o "$2" "$1"
  fi
}

find_tool() {
  name="$1"
  if command -v "$name" >/dev/null 2>&1; then command -v "$name"; return; fi
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$root" ] || continue
    hit=$(find "$root" -type f -name "$name" 2>/dev/null | sort -r | head -n1 || true)
    [ -n "$hit" ] && { echo "$hit"; return; }
  done
  echo ""
}

say 'Checking for adb'
ADB=$(find_tool adb)
[ -n "$ADB" ] || die 'adb was not found. Install Android platform-tools and re-run.'
ok "adb: $ADB"

say 'Looking for a connected device'
"$ADB" start-server >/dev/null 2>&1 || true
DEVICES_OUT=$("$ADB" devices)
echo "$DEVICES_OUT" | sed 's/^/    /'

READY=""; OFFLINE=""; UNAUTH=""
while read -r serial state _; do
  [ -n "${serial:-}" ] || continue
  case "$state" in
    device)       READY="${READY:+$READY }$serial" ;;
    offline)      OFFLINE="${OFFLINE:+$OFFLINE }$serial" ;;
    unauthorized) UNAUTH="${UNAUTH:+$UNAUTH }$serial" ;;
  esac
done <<< "$(echo "$DEVICES_OUT" | tail -n +2)"

[ -z "$UNAUTH" ]  || die "Device not authorised. Unlock the phone and accept the USB debugging prompt, then re-run. ($UNAUTH)"
[ -z "$OFFLINE" ] || die "Device is offline. Reconnect the cable or restart adb, then re-run. ($OFFLINE)"
[ -n "$READY" ]   || die 'No device connected. Enable USB debugging, plug the phone in, accept the prompt, then re-run.'
[ "$(echo "$READY" | wc -w)" -eq 1 ] || die "Multiple devices connected; refusing to guess. ($READY)"
SERIAL="$READY"
ok "one device: $SERIAL"

say 'Reading the installed package'
DUMP=$("$ADB" -s "$SERIAL" shell dumpsys package "$EXPECTED_PACKAGE" 2>&1 || true)
echo "$DUMP" | grep -q 'versionCode=' || die "Package $EXPECTED_PACKAGE is not installed on the device."
INSTALLED_CODE=$(echo "$DUMP" | grep -o 'versionCode=[0-9]*' | head -n1 | cut -d= -f2)
INSTALLED_NAME=$(echo "$DUMP" | grep -o 'versionName=[^ ]*' | head -n1 | cut -d= -f2)
ok "installed: $INSTALLED_NAME (versionCode $INSTALLED_CODE)"

WORKDIR="${TMPDIR:-/tmp}/riy-usb-update"
mkdir -p "$WORKDIR"

say 'Locating the update APK'
if [ -n "$APK_PATH" ]; then
  [ -f "$APK_PATH" ] || die "APK not found: $APK_PATH"
  APK_FILE="$APK_PATH"
  APK_NAME=$(basename "$APK_FILE")
elif [ "$PULL" -eq 1 ]; then
  [ -n "$VERSION" ] || die '--pull needs a version so the file name is known.'
  APK_NAME="riy-v$VERSION.apk"
  REMOTE="/sdcard/Download/Riy/$APK_NAME"
  say "Pulling $REMOTE from the phone"
  "$ADB" -s "$SERIAL" pull "$REMOTE" "$WORKDIR/$APK_NAME" || \
    die "Could not pull $REMOTE. Download the APK from the release page and use --apk."
  APK_FILE="$WORKDIR/$APK_NAME"
else
  TAG="$VERSION"
  if [ -z "$TAG" ]; then
    LATEST_JSON=$(github_api_get "https://api.github.com/repos/$OWNER/$REPO/releases/latest" 2>/dev/null) || \
      die 'Could not read the latest release. Check the network connection and try again.'
    TAG=$(printf '%s' "$LATEST_JSON" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
  fi
  case "$TAG" in
    v[0-9]*|V[0-9]*) ;;
    *) die "Refusing to use an invalid release tag: $TAG" ;;
  esac
  [ -n "$TAG" ] || die 'Could not determine the latest release tag.'
  ASSETS_JSON=$(github_api_get "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG" 2>/dev/null) || \
      die "Could not read release $TAG. Check the network connection and try again."
  URL=$(printf '%s' "$ASSETS_JSON" | tr ',' '\n' | grep 'browser_download_url' | grep '\.apk' | head -n1 | sed 's/.*"browser_download_url": *"\([^"]*\)".*/\1/')
  [ -n "$URL" ] || die "Release $TAG has no APK asset."
  APK_NAME=$(basename "$URL")
  say "Downloading $APK_NAME"
  github_download "$URL" "$WORKDIR/$APK_NAME" || \
    die "Could not download $APK_NAME. Check the network connection and try again."
  APK_FILE="$WORKDIR/$APK_NAME"
fi

SIZE=$(wc -c < "$APK_FILE" | tr -d ' ')
[ "$SIZE" -gt 0 ] || die 'The APK file is empty.'
ok "$APK_NAME ($((SIZE/1024/1024)) MB)"

say 'Verifying the APK'
AAPT2=$(find_tool aapt2)
if [ -n "$AAPT2" ]; then
  if ! BADGING=$("$AAPT2" dump badging "$APK_FILE" 2>/dev/null); then
    die 'The APK could not be read as an Android package. Download it again; it may be truncated or corrupt.'
  fi
  APK_PKG=$(printf '%s' "$BADGING" | sed -n "s/package: name='\([^']*\)'.*/\1/p")
  APK_CODE=$(printf '%s' "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
  ok "aapt2: package=$APK_PKG versionCode=$APK_CODE"
  if [ -n "$APK_PKG" ] && [ "$APK_PKG" != "$EXPECTED_PACKAGE" ]; then
    die "Refusing to install: this APK is '$APK_PKG', not '$EXPECTED_PACKAGE'."
  fi
  if [ -n "$APK_CODE" ]; then
    if [ "$APK_CODE" -eq "$INSTALLED_CODE" ]; then
      warn "Already the installed version ($APK_CODE)."
      exit 0
    fi
    if [ "$APK_CODE" -lt "$INSTALLED_CODE" ]; then
      die "Refusing to install: APK versionCode $APK_CODE is OLDER than installed $INSTALLED_CODE."
    fi
  fi
else
  warn 'aapt2 not found; Android will enforce package and version itself.'
fi

APKSIGNER=$(find_tool apksigner)
if [ -n "$APKSIGNER" ]; then
  if ! CERTS=$("$APKSIGNER" verify --print-certs "$APK_FILE" 2>/dev/null); then
    die 'The APK failed signature verification before installation. Download it again; it may be truncated or corrupt.'
  fi
  DIGEST=$(printf '%s' "$CERTS" | grep -m1 -o 'certificate SHA-256 digest: .*' | cut -d' ' -f5)
  [ -n "$DIGEST" ] && ok "APK signer SHA-256: $DIGEST"
fi

say 'Installing (in place, app data preserved)'
echo "    adb -s $SERIAL install -r \"$APK_FILE\""
set +e
OUT=$("$ADB" -s "$SERIAL" install -r "$APK_FILE" 2>&1)
CODE=$?
set -e
echo "$OUT" | sed 's/^/    /'

if [ $CODE -eq 0 ] && echo "$OUT" | grep -q 'Success'; then
  ok 'Update installed successfully.'
  printf '\n    \033[32mRe-open Riy on the phone to finish.\033[0m\n'
  exit 0
fi

case "$OUT" in
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    die 'The APK is signed with a different certificate than the installed app. Do not uninstall (that would delete your data); sign the release with the same key.' ;;
  *INSTALL_FAILED_VERSION_DOWNGRADE*)
    die 'The APK is older than the installed version. Do not force it unless intended.' ;;
  *INSTALL_FAILED_USER_RESTRICTED*)
    die 'The device refused the installation under its administrator/user policy. No data was uninstalled. Check the authorized profile or device-owner policy, then re-run.' ;;
  *INSTALL_FAILED_PERMISSION_DENIED*)
    die 'The device refused installation permission. Re-authorize USB debugging or use the authorized profile, then re-run.' ;;
  *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
    die 'Not enough free storage on the device.' ;;
  *INSTALL_FAILED_NO_MATCHING_ABIS*)
    die 'The APK does not support this device ABI.' ;;
  *INSTALL_FAILED_VERIFICATION_FAILURE*)
    die 'The APK failed signature verification.' ;;
  *INSTALL_CANCELED*)
    die 'The install was cancelled.' ;;
  *device*not*found*|*no*devices*|*device*offline*|*unauthorized*|*closed*|*connection*reset*|*connection*refused*|*broken*pipe*)
    die 'The phone disconnected or lost ADB authorization during installation. Reconnect USB, accept the debugging prompt if shown, verify the installed version, then re-run.' ;;
  *)
    die "adb install failed with exit code $CODE." ;;
esac