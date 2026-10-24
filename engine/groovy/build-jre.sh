#!/usr/bin/env bash
# Builds the trimmed Java runtime shipped with the Groovy engines (desktop "Groovy pack").
# Usage: engine/groovy/build-jre.sh <output-dir>      (JAVA_HOME must be a JDK 17+ with jmods)
#
# Module set: what jdeps reports for the engine jars, plus modules user scripts commonly need at
# runtime (HTTP client, crypto, locale data for date/number formatting, extra charsets, zip FS).
set -euo pipefail
OUT="${1:?output dir}"
JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK}"
MODULES="java.base,java.compiler,java.desktop,java.management,java.management.rmi,java.naming,java.prefs,java.scripting,java.sql,java.xml,java.logging,java.net.http,java.instrument,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.zipfs,jdk.net,jdk.xml.dom"
rm -rf "$OUT"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
  --compress=2 --generate-cds-archive --output "$OUT"
# The no-compressed-oops archive is only used with heaps > 32 GB.
rm -f "$OUT"/lib/server/classes_nocoops.jsa
"$OUT/bin/java" -version
du -sh "$OUT"
