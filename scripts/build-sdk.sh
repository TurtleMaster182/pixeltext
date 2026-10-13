#!/usr/bin/env sh
# Reproducible SDK-only alternative for environments without Gradle.
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk_dir" ]; then echo 'Set ANDROID_HOME to an Android SDK with platform 35 and build tools 35.0.0.' >&2; exit 1; fi
build_tools="$sdk_dir/build-tools/35.0.0"
android_jar="$sdk_dir/platforms/android-35/android.jar"
build_dir="$project_dir/app/build/sdk"
mkdir -p "$build_dir/generated" "$build_dir/classes" "$build_dir/dex"
"$build_tools/aapt2" compile --dir "$project_dir/app/src/main/res" -o "$build_dir/resources.zip"
python3 - "$project_dir" "$build_dir" <<'PY'
import sys
from pathlib import Path
project, build = map(Path, sys.argv[1:])
manifest=(project/'app/src/main/AndroidManifest.xml').read_text().replace('${applicationId}', 'ro.pixeltext')
manifest=manifest.replace('<manifest ', '<manifest package="ro.pixeltext" android:versionCode="2" android:versionName="1.0.1" ', 1)
manifest=manifest.replace('    <application', '    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />\n    <application', 1)
(build/'AndroidManifest.xml').write_text(manifest)
PY
"$build_tools/aapt2" link -o "$build_dir/base.apk" -I "$android_jar" --manifest "$build_dir/AndroidManifest.xml" --java "$build_dir/generated" "$build_dir/resources.zip"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -classpath "$android_jar" -d "$build_dir/classes" "$project_dir"/app/src/main/java/ro/pixeltext/*.java "$project_dir"/app/src/main/java/ro/pixeltext/core/*.java "$build_dir/generated/ro/pixeltext/R.java"
python3 - "$build_dir" <<'PY'
import sys, zipfile
from pathlib import Path
build=Path(sys.argv[1])
with zipfile.ZipFile(build/'classes.jar','w',zipfile.ZIP_DEFLATED) as out:
    for p in (build/'classes').rglob('*.class'): out.write(p,p.relative_to(build/'classes'))
PY
"$build_tools/d8" --release --min-api 26 --lib "$android_jar" --output "$build_dir/dex" "$build_dir/classes.jar"
python3 - "$build_dir" <<'PY'
import sys, zipfile
from pathlib import Path
build=Path(sys.argv[1])
with zipfile.ZipFile(build/'base.apk','a',zipfile.ZIP_DEFLATED) as out:
    for p in (build/'dex').glob('*.dex'): out.write(p,p.name)
PY
"$build_tools/zipalign" -f 4 "$build_dir/base.apk" "$build_dir/aligned.apk"
if [ ! -f "$build_dir/debug.jks" ]; then
    keytool -genkeypair -keystore "$build_dir/debug.jks" -alias androiddebugkey -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000 -noprompt
fi
"$build_tools/apksigner" sign --ks "$build_dir/debug.jks" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$build_dir/PixelText-debug.apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" verify --verbose "$build_dir/PixelText-debug.apk"
echo "$build_dir/PixelText-debug.apk"
