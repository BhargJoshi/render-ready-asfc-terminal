#!/usr/bin/env bash
# Builds the backend, then runs the browser-level integration test. Requires: JDK 17+, Node 18+, `npm install` done in this folder.
set -euo pipefail
cd "$(dirname "$0")/.."
./backend/build.sh >/dev/null
node test-e2e/e2e.js
