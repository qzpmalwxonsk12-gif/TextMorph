# Source snapshot

Retain the reviewed source with signing passwords sanitized and signing keys omitted.

Original local source files are unchanged. This private backup excludes credentials, signing keys, local configuration, dependencies, build caches, runtime data, and unrelated research/old backup folders. No build or functionality test was run as part of this upload.

Signing passwords in TextMorph/build_apk.ps1 were replaced with `$env:ANDROID_KEYSTORE_PASSWORD`.

## Excluded paths

- `TextMorph/build_out/`
- `TextMorph/lspatch_tools/`
- `TextMorph/pulled_kite3/`
- `TextMorph/LSPatch-Manager-Android15.apk`
- `TextMorph/TextMorph-Universal.apk`
- `TextMorph/TextMorph-Universal.apk.idsig`