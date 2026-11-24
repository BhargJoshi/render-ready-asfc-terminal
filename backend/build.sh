#!/usr/bin/env bash
# Dependency-light build: javac + the Jackson jars (+ JUnit console launcher for tests).
# Usage: ./build.sh [test]     Jars are looked up in $LIB_DIR (default: ./lib, then /usr/share/java).
set -euo pipefail
cd "$(dirname "$0")"
find_jar() { for d in "${LIB_DIR:-}" ./lib /usr/share/java; do [ -n "$d" ] && for f in "$d"/$1*.jar; do [ -f "$f" ] && { echo "$f"; return; }; done; done; echo "missing jar: $1" >&2; exit 1; }
CP="$(find_jar jackson-core):$(find_jar jackson-databind):$(find_jar jackson-annotations)"
rm -rf target && mkdir -p target/classes target/test-classes
echo "target/classes:$CP" > target/classpath.txt   # used by run.sh
javac --release 17 -Xlint:all -d target/classes -cp "$CP" $(find src/main -name '*.java')
echo "BUILD OK: target/classes"
if [ "${1:-}" = "test" ]; then
  JUNIT="$(find_jar junit-platform-console-standalone)"
  javac --release 17 -d target/test-classes -cp "target/classes:$CP:$JUNIT" $(find src/test -name '*.java')
  cp -r src/test/resources/* target/test-classes/
  java -jar "$JUNIT" execute --class-path "target/classes:target/test-classes:$CP" --scan-class-path target/test-classes --disable-banner --details=tree
fi
