# TextMorph

Android text transformation app/module prototype with rewrite rules, accessibility/overlay helpers, and hook integration.

## Source layout

- pp/: Android application and module source.
- xposed_stub/src/: compile-only API source stubs used by the Windows build script.
- uild_apk.ps1: Windows APK build script.
- pc_patcher.ps1 and Patch-Any-App-On-PC.bat: PC-side patching helpers; external tools are not bundled.

## Build setup

Configure the Android SDK/build-tools and Java paths near the top of uild_apk.ps1 for your computer. Its project directory is resolved from the script location. Set the ANDROID_KEYSTORE_PASSWORD environment variable before signing. Private signing keys, passwords, downloaded APKs, generated builds, and external binary tools are excluded from this source snapshot.

See BACKUP_NOTES.md for snapshot scope.
