#!/bin/sh
# Builds the mod on Linux/macOS. Output: build/libs/manhattan-<version>.jar
# Uses the portable JDK 25 in tools/jdk-25* if it is there (not in git), otherwise your own JAVA_HOME.
# (install.bat is Windows/Modrinth-profile specific: on other systems copy the jar into your instance's mods folder.)
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
for d in "$ROOT"/tools/jdk-25*; do
  if [ -d "$d/Contents/Home" ]; then JAVA_HOME="$d/Contents/Home"; elif [ -d "$d" ]; then JAVA_HOME="$d"; fi
done
[ -n "$JAVA_HOME" ] && export JAVA_HOME
exec "$ROOT/gradlew" build "$@"
