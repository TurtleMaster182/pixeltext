# Validation

- `assembleDebug`: successful with Gradle 8.11.1, Android Gradle plugin 8.9.2, JDK 17, and Android SDK 35.
- `assembleDebugAndroidTest`: successful; the native PNG integration test APK compiles.
- `lintDebug`: successful with no issues found.
- `scripts/test-codec.sh`: 1,480 checks passed independently of Android and Gradle, including UTF-8/UTF-16 BOM handling, explicit Windows-1250 import, CRLF and blank-line preservation through every compression mode.
- Desktop reference tool: encoded `samples/example.txt` in predictive mode, reopened the 512×512 PNG, decoded it, and compared output byte for byte. The 5,056-byte input produced a 3,102-byte arithmetic payload.
- SDK-only build: successfully compiled, dexed, aligned, and signed the application.
- Final APK: verified by Android SDK `apksigner`; package `ro.pixeltext`, version `1.0.1`, minimum API 26, target API 35. The signing certificate matches the checked-in version 1.0.0 APK for normal updates.

The repeated-text benchmark in the codec suite is intentionally highly compressible. For that specific 780,000-byte input, adaptive arithmetic produced 124,595 bytes and DEFLATE produced 2,736 bytes. These results are not a general compression guarantee.

Native instrumentation is run by the GitHub workflow on an Android 35 emulator. It tests PNG serialization, imports of multiline UTF-16 and Windows-1250 files, and multiline paste through the actual editor followed by image generation. The local environment has no attached physical Android device. The application performs its own native PNG round-trip verification after each generation before export becomes available.
