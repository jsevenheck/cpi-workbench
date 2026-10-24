#!/usr/bin/env bash
# Builds the native XML engines with GraalVM Native Image.
# Usage: engine/native/build-native.sh [he99|he12 ...]   (default: both)
# Requires GRAALVM_HOME (or native-image on PATH) and the shaded jars (mvn -DskipTests package).
set -euo pipefail
cd "$(dirname "$0")/.."
NI="${GRAALVM_HOME:+$GRAALVM_HOME/bin/}native-image"
OUT=native/out
mkdir -p "$OUT"
for id in "${@:-he99 he12}"; do
  for v in $id; do
    echo "== building cpiwb-xml-$v"
    "$NI" --no-fallback -march=compatibility ${NATIVE_OPT:-} \
      -H:+ReportExceptionStackTraces \
      -jar "xml-$v/target/cpiwb-xml-$v.jar" \
      -o "$OUT/cpiwb-xml-$v"
  done
done
ls -la "$OUT"
