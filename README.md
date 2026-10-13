# PixelText

An offline Android app that compresses UTF-8 text with an adaptive probability model, stores the result in an exact **512×512 PNG**, and recovers the original text from that PNG.

## Features

- Type, paste, or import a UTF-8 `.txt` file.
- **Predictive:** order-1 adaptive arithmetic coding learns the probability of each next byte from the preceding byte. No downloaded language model or language-specific dictionary is required.
- **Automatic:** compare predictive coding, DEFLATE, and raw UTF-8; keep the smallest payload that fits.
- **DEFLATE:** dictionary compression followed by entropy coding, useful for repeated phrases and larger documents.
- Preview, save, or share the generated PNG as a file.
- Import a PNG, verify checksums, recover text, copy it, or export the complete `.txt` file.
- Support Romanian diacritics, CJK, emoji, whitespace, and empty input without changing content.
- Compression and file operations run on a worker thread. Large recovered text has a bounded display preview and complete file export.
- No Internet, camera, or broad storage permissions; Android's document picker handles file access.

## Install

Download [PixelText-1.0.0.apk](downloads/PixelText-1.0.0.apk), open the file on an Android device, and install it. Android **8.0/API 26 or later** is required. The supplied APK is signed with a development key. Production/store distribution requires a maintained release signing key. Its SHA-256 is recorded in [downloads/SHA256SUMS](downloads/SHA256SUMS).

Encode: enter text, select a mode, press **Generate 512 × 512 PNG**, then save the PNG. Decode: switch to **Decode**, press **Open PNG & decode**, and select the original file.

**This is a data image, not a QR code.** It must be transferred as the original PNG. A camera photograph, screenshot, resized image, edited image, JPEG, or a messenger's recompressed photo will not reliably preserve the bytes. Use a document/file attachment when sharing. Checksums detect damage; the format does not correct errors or encrypt text.

## Capacity

Opaque RGB stores three bytes per pixel. Alpha is fixed at 255 to avoid transparency and premultiplication losses.

`512 × 512 × 3 = 786,432 bytes`, minus a 32-byte header, leaves **786,400 bytes** of payload, approximately **768 KiB**. This is the pixel payload capacity, not the PNG file size. PNG adds its own compression and metadata.

The original UTF-8 text may be larger when compression succeeds. Input and decoded output are limited to **8 MiB** to bound memory use. There is no fixed character capacity: both UTF-8 byte length and compressibility matter. Automatic mode uses raw UTF-8 when compression would expand an input that already fits. Explicit Predictive/DEFLATE modes retain the selected compressor even if short input expands.

## Build

Use JDK 17, Android SDK platform 35, and build tools 35.0.0. The checked-in Gradle wrapper pins Gradle 8.11.1 with a distribution SHA-256; the Android Gradle plugin is 8.9.2. Import this directory into Android Studio, or set `ANDROID_HOME` to the SDK directory and run:

```sh
./gradlew assembleDebug lintDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug lintDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. `local.properties` may instead specify `sdk.dir`; it is deliberately excluded from version control and the source archive.

The SDK-only build alternative is `scripts/build-sdk.sh`; it uses `aapt2`, the Java compiler, D8, zipalign, and apksigner without Gradle. It creates a development-signed APK at `app/build/sdk/PixelText-debug.apk`. Both build paths use the same application source and resource files.

## Test

The codec suite needs only JDK 17 and does not need the Android SDK:

```sh
sh scripts/test-codec.sh
```

Alternatively: `./gradlew :app:codecTest`. The suite checks deterministic random byte and Unicode round trips, all three modes, pixel packing, probability model rescaling, empty input, corruption, unknown versions, malformed UTF-8, truncated input, decompression limits, the 8 MiB limit, and the exact image capacity boundary.

Native Android PNG integration tests build and run on a connected device/emulator:

```sh
./gradlew assembleDebugAndroidTest connectedDebugAndroidTest
```

The app also serializes, reopens, and decodes every generated PNG before enabling export. The native integration test covers nine PNG round trips: empty, multilingual, and repeated text in all modes.

## Desktop interoperability

The dependency-free reference tool uses the same codec and standard Java ImageIO:

```sh
mkdir -p app/build/cli
javac -encoding UTF-8 -d app/build/cli app/src/main/java/ro/pixeltext/core/*.java tools/PixelTextCli.java
java -cp app/build/cli PixelTextCli encode samples/example.txt example.png predictive
java -cp app/build/cli PixelTextCli decode example.png recovered.txt
```

Optional encoder modes are `auto`, `predictive`, and `deflate`. `samples/example.png` contains the exact text in `samples/example.txt` and can be imported by the Android app.

## GitHub builds

The [Android build workflow](https://github.com/TurtleMaster182/pixeltext/actions/workflows/android.yml) runs the codec suite, builds the APK, runs Android lint, and uploads the APK as a build artifact on pushes, pull requests, or a manual run. Successful runs provide the `PixelText-debug-apk` artifact.

Clone the public repository to build or change the app:

```sh
git clone https://github.com/TurtleMaster182/pixeltext.git
cd pixeltext
./gradlew assembleDebug lintDebug
```

The checked-in APK is a snapshot of version 1.0.0. New builds are available from workflow artifacts. No tokens or private signing keys are included in the repository.

## Structure

| Path | Purpose |
| --- | --- |
| `app/src/main/java/ro/pixeltext/core/` | Android-independent compression, checksums, format, RGB packing |
| `app/src/main/java/ro/pixeltext/` | Native UI, exact PNG transport, read-only share provider |
| `app/src/test/` | Dependency-free codec suite |
| `app/src/androidTest/` | Actual Android Bitmap PNG integration test |
| `tools/PixelTextCli.java` | Desktop PNG encoder/decoder |
| `docs/FORMAT.md` | Versioned binary format and adaptive probability model |
| `scripts/` | Test and SDK-only build tools |

Drafts and the latest decoded document are stored in app-private storage. Shared images use separate immutable filenames so an old URI grant cannot expose a future document. Uninstalling or clearing app data removes private drafts; exported files remain at their chosen destinations. Shared images use app cache and can be evicted by Android.

## Build references

- [Android Gradle plugin compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [Android document picker APIs](https://developer.android.com/training/data-storage/shared/documents-files)
- [Gradle wrapper documentation](https://docs.gradle.org/8.11.1/userguide/gradle_wrapper.html)

The Gradle wrapper retains its upstream license notices.
