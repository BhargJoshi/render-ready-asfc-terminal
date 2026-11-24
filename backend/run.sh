#!/usr/bin/env bash
# Starts the Ashford Terminal backend (and serves the front end from ../frontend).
# Secrets/config come from environment variables; if ../.env exists it is loaded first (simple KEY=value lines).
set -euo pipefail
cd "$(dirname "$0")"
if [ -f ../.env ]; then set -a; . ../.env; set +a; fi
export STATIC_DIR="${STATIC_DIR:-$(cd ../frontend && pwd)}"
if [ -d target/lib ] && [ -d target/classes ]; then CP="target/classes:target/lib/*"     # built with Maven
else [ -f target/classpath.txt ] || ./build.sh >/dev/null; CP="$(cat target/classpath.txt)"; fi   # built with build.sh
exec java -cp "$CP" com.ashford.terminal.App
