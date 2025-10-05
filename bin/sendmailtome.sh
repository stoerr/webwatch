#!/usr/bin/env bash
set -euo pipefail

# Configuration
CONFIG="$HOME/.secrets/.sendmailtome"

if [[ ! -f "$CONFIG" ]]; then
  echo "Config file not found: $CONFIG" >&2
  echo "Create it with lines like:" >&2
  echo "  FROM=me@example.com" >&2
  echo "  TO=me@example.com" >&2
  echo "  SMTP=smtp.example.com:587" >&2
  echo "  USER=me@example.com" >&2
  echo "  PASS='secretpass'" >&2
  echo "  TLS=yes" >&2
  exit 1
fi

# Warn if config file permissions are too permissive (best-effort)
if perms=$(stat -c "%a" "$CONFIG" 2>/dev/null || true); then
  if [[ "$perms" != "600" ]]; then
    echo "Warning: $CONFIG permissions are not 600 (current: $perms). Consider chmod 600 $CONFIG" >&2
  fi
fi

# shellcheck disable=SC1090
source "$CONFIG"

# Validate required variables
: "${FROM:?FROM must be set in $CONFIG}"
: "${TO:?TO must be set in $CONFIG}"
: "${SMTP:?SMTP must be set in $CONFIG}"
: "${USER:?USER must be set in $CONFIG}"
: "${PASS:?PASS must be set in $CONFIG}"
TLS="${TLS:-yes}"

# Subject from arguments
if [[ $# -lt 1 ]]; then
  echo "Usage: $(basename "$0") <subject>" >&2
  echo "Body is read from stdin." >&2
  exit 2
fi
SUBJECT="$*"

# Read body from stdin into a temp file
TMPFILE="$(mktemp)"
trap 'rm -f "$TMPFILE"' EXIT
cat - > "$TMPFILE" || true

# If no stdin provided, ensure at least an empty body
if [[ ! -s "$TMPFILE" ]]; then
  echo "(no body provided)" > "$TMPFILE"
fi

# Ensure sendemail is available
if ! command -v sendemail >/dev/null 2>&1; then
  echo "sendemail command not found. Please install 'sendemail'." >&2
  exit 3
fi

# Build command (do not echo the password)
CMD=(sendemail -f "$FROM" -t "$TO" -u "$SUBJECT" -m "$(cat "$TMPFILE")" -s "$SMTP" -xu "$USER" -xp "$PASS" -o tls="$TLS" -o message-charset=CHARSET)

# Print a masked summary of what will be run
echo "Sending mail:"
echo "  From: $FROM"
echo "  To:   $TO"
echo "  SMTP: $SMTP"
echo "  User: $USER"
echo "  TLS:  $TLS"
echo "  Subject: $SUBJECT"
echo "  Body size: $(wc -c < "$TMPFILE") bytes"
echo "  (password not shown)"
echo

# Execute
"${CMD[@]}"
exit_code=$?

if [[ $exit_code -ne 0 ]]; then
  echo "sendemail exited with code $exit_code" >&2
fi

exit "$exit_code"

