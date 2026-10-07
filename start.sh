#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

echo "========================================"
echo "       GCT Report Parser"
echo "========================================"
echo

PYTHON_BIN=""
for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
        if "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
            PYTHON_BIN="$(command -v "$candidate")"
            break
        fi
    fi
done

if [[ -z "$PYTHON_BIN" ]]; then
    echo "ERROR: Python 3.10 or newer is required."
    echo "Install Python 3.10+ and run this command again."
    exit 1
fi

VENV_DIR="$APP_DIR/.venv"
VENV_PYTHON="$VENV_DIR/bin/python"
REQ_HASH_FILE="$VENV_DIR/.requirements.sha256"

if [[ ! -x "$VENV_PYTHON" ]]; then
    echo "First-time setup detected."
    echo "Creating application environment..."
    "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

CURRENT_REQ_HASH="$(sha256sum requirements.txt | awk '{print $1}')"
INSTALLED_REQ_HASH=""
if [[ -f "$REQ_HASH_FILE" ]]; then
    INSTALLED_REQ_HASH="$(cat "$REQ_HASH_FILE")"
fi

if [[ "$CURRENT_REQ_HASH" != "$INSTALLED_REQ_HASH" ]]; then
    echo "Installing application dependencies..."
    "$VENV_PYTHON" -m pip install --upgrade pip
    "$VENV_PYTHON" -m pip install -r requirements.txt
    printf '%s' "$CURRENT_REQ_HASH" > "$REQ_HASH_FILE"
else
    echo "Environment ready."
fi

HOST="\${HOST:-127.0.0.1}"
PORT="\${PORT:-8080}"

echo
echo "Starting GCT Report Parser..."
echo
echo "Application: http://\${HOST}:\${PORT}"
echo "Press Ctrl+C to stop."
echo

exec env HOST="$HOST" PORT="$PORT" "$VENV_PYTHON" app.py
