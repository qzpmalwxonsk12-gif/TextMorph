# TextMorph

An Android text-transformation prototype that stores rewrite rules and integrates an application UI, accessibility/overlay helpers and Xposed-style in-app hooks. It is intended for controlled text/presentation experiments on apps and devices you are authorised to modify.

The source has two build descriptions with different version/SDK values. The custom Windows APK script and Android manifest describe **2.0.0**, min SDK 28/target 35; the retained Gradle app file describes **1.0.0**, min SDK 30/target 35. Treat them as distinct build paths until reconciled, not interchangeable release metadata.

## What is included

- Rule models/storage and a rule-management activity.
- A rule content provider for cross-app/module access.
- Hook code for Android text-rendering paths.
- Accessibility-service and overlay helpers, including colour sampling.
- Compile-only Xposed API source stubs for the custom build script.
- PC-side patching helpers; their external binary tools and downloaded APK inputs are not bundled.

Hooking or accessibility cannot be assumed to rewrite every app or renderer. Root/module frameworks, repackaged apps, signatures, custom drawing and Android restrictions affect behaviour. The source is not evidence of universal compatibility or a measured 120 Hz result.

## Source structure

| Path | Purpose |
|---|---|
| `app/src/main/java/com/antigravity/textmorph/` | UI, rules, provider, service and hook implementation |
| `app/src/main/res/` | Android UI resources |
| `app/src/main/assets/xposed_init` | Hook/module entry declaration |
| `xposed_stub/src/` | Compile-time API declarations used by the custom script |
| `build_apk.ps1` | Windows compile/package/sign workflow |
| `pc_patcher.ps1` / `Patch-Any-App-On-PC.bat` | Existing PC-side helper workflow |

## Build setup

The custom script expects Java, Android platform 35 and Android build-tools 35.0.0 tools such as `aapt2`, `d8`, `zipalign` and `apksigner`. Review its SDK/Java path variables for your machine. Its project directory resolves from `$PSScriptRoot` in this uploaded copy.

Set a signing password in your current shell before running the reviewed script:

```powershell
$env:ANDROID_KEYSTORE_PASSWORD = Read-Host "Local build signing password"
.\build_apk.ps1
```

Both keytool and apksigner use that environment variable in this repository. Signing keys and hardcoded signing-password arguments are omitted. Keep any signing material you need for updates separately; do not commit it. The script generates output in `build_out/` and a local APK, neither of which is shipped here.

The Gradle files are retained as another source configuration, but no wrapper launcher is included in this snapshot. Do not assume that route packages exactly the same application as the custom script.

## Run and configure

After building/installing a compatible app/module, review its rule settings and grant only the Android capabilities required by the path you intend to test. Overlay permission, the accessibility service and a compatible hook/module environment are separate mechanisms. Merely installing the standalone activity does not establish that hooks are loaded inside another app.

Use simple, reversible text examples first and check the actual target UI. Review the PC patching helpers and supply their legitimate external dependencies/inputs before using them; this README does not execute or install them.

## Limits and data handling

The Android manifest exposes the rule provider. Rules and transformed text should not be treated as a protected secret store. Existing application signatures and update/install behaviour can differ after repackaging. No downloaded target APKs, credentials or user runtime data are part of this source upload.

The source and build instructions were reviewed for this README; no new APK build, target-app compatibility test, root/module installation or physical-device verification was performed. See [BACKUP_NOTES.md](BACKUP_NOTES.md) for snapshot scope. This documentation assigns no new license to third-party software or APIs.
