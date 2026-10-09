# USB / ADB update (Riy)

Use this when the app says **"Installation restricted"** instead of opening the
Android installer, because an administrator owns "install unknown apps" on that
device (`Settings` shows *"Blocked by your IT admin"* for that switch).

This is the **supported development update path**. It is not a workaround of any
security control: `adb install` is performed by your own computer, with your own
USB debugging authorisation, exactly as Android intends.

## Windows

```powershell
.\usb-update.ps1                 # latest GitHub release
.\usb-update.ps1 -Version 2.7.0  # a specific version
.\usb-update.ps1 -ApkPath C:\build\riy-v2.7.0.apk
.\usb-update.ps1 -Version 2.7.0 -PullFromPhone   # pull the APK off the phone
```

## macOS / Linux

```bash
chmod +x usb-update.sh
./usb-update.sh                  # latest GitHub release
./usb-update.sh 2.7.0            # a specific version
./usb-update.sh --apk ./riy-v2.7.0.apk
./usb-update.sh 2.7.0 --pull     # pull the APK off the phone
```

## What it does

1. finds `adb` (PATH, then the standard Android SDK locations);
2. runs `adb devices` and requires **exactly one** device in state `device`
   (it explains `offline` and `unauthorized` separately, and refuses to guess
   between several devices);
3. reads the installed `com.vishal.riy` version from `dumpsys package`;
4. obtains the APK — the release asset, a local file, or a pull from
   `/sdcard/Download/Riy/`;
5. verifies package name, versionCode and signing certificate with
   `aapt2` / `apksigner` when they are available;
6. runs **`adb install -r`**, so the update is in place and all app data —
   settings, blocker configuration, accessibility grants — survives.

If the GitHub release repository is private, set a read-only `GITHUB_TOKEN`
environment variable before running either helper. The token is used only for
release-asset requests; it is never written to disk or displayed.

## Safety rules it will not break

| Rule | Why |
|---|---|
| The target package is the constant `com.vishal.riy` | An unrelated APK is never installed. |
| A package-name mismatch aborts | A foreign file can never be installed. |
| A certificate mismatch is reported, never bypassed | Android will refuse it; the fix is the correct signing key, not a workaround. |
| `install -r` only | An update, never an uninstall — uninstalling would delete the user's data. |
| No arbitrary shell | Tools are called with explicit argument arrays; nothing is passed to a shell for evaluation. |
| Older version refuses by default | `-d` is never added silently. |

## Troubleshooting the states the app shows

| On the phone | Meaning |
|---|---|
| **USB not connected** | No USB cable detected. Nothing is wrong yet. |
| **Ready to update** | A USB device is attached and the APK is verified and staged. Run the command. |
| **Update successful** | The installed version already equals the release version. |
| **Installation blocked** | The APK is not a valid update for this install (see the message). |

If `adb devices` shows the device as `unauthorized`, unlock the phone and accept
the USB debugging prompt — that authorisation cannot be granted from the PC.