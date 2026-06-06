#!/usr/bin/env bash
set -euo pipefail

# ==========================================================================
# Build the multica CLI, install it to /usr/local/bin, and restart the daemon.
# Usage: bash scripts/redeploy-daemon.sh
# ==========================================================================

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BINARY_SRC="$REPO_ROOT/server/bin/multica"
BINARY_DST="/usr/local/bin/multica"

echo "==> Building multica..."
cd "$REPO_ROOT"
make build

echo "==> Installing $BINARY_SRC -> $BINARY_DST"
cp "$BINARY_SRC" "$BINARY_DST"

echo "==> Restarting daemon..."
# Stop any running daemon gracefully (SIGTERM). Ignore errors if not running.
pkill -SIGTERM -x multica 2>/dev/null || true

# Give it a moment to shut down cleanly.
sleep 1

# Start the daemon in the background, inheriting the current shell environment.
"$BINARY_DST" daemon &
DAEMON_PID=$!

echo "==> Daemon started (pid $DAEMON_PID)"
