#!/usr/bin/env sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
classes_dir="$project_dir/app/build/codec-tests"
mkdir -p "$classes_dir"
javac -encoding UTF-8 -d "$classes_dir" "$project_dir"/app/src/main/java/ro/pixeltext/core/*.java "$project_dir"/app/src/test/java/ro/pixeltext/core/*.java
java -Xmx256m -cp "$classes_dir" ro.pixeltext.core.CodecSelfTest
