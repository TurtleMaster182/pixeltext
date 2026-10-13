# Validation

- `assembleDebug`: successful with Gradle 8.11.1, Android Gradle plugin 8.9.2, JDK 17, and Android SDK 35.
- `assembleDebugAndroidTest`: successful; the native PNG integration test APK compiles.
- `lintDebug`: successful with no issues found.
- `:app:codecTest`: 1,266 checks passed.
- `scripts/test-codec.sh`: 1,266 checks passed independently of Android and Gradle.
- Desktop reference tool: encoded `samples/example.txt` in predictive mode, reopened the 512×512 PNG, decoded it, and compared output byte for byte. The 5,056-byte input produced a 3,102-byte arithmetic payload.
- SDK-only build: successfully compiled, dexed, aligned, and signed the application.
- Final APK: verified by Android SDK `apksigner`; package `ro.pixeltext`, version `1.0.0`, minimum API 26, target API 35.

The repeated-text benchmark in the codec suite is intentionally highly compressible. For that specific 780,000-byte input, adaptive arithmetic produced 124,595 bytes and DEFLATE produced 2,736 bytes. These results are not a general compression guarantee.

No Android device or emulator was attached. Device installation, visual UI checks, and the native instrumentation test were not executed in the build environment. The application performs its own native PNG round-trip verification after each generation before export becomes available.
