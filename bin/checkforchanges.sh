#!/usr/bin/env bash
# Run the webwatch jar with --checkpages; if it exits successfully (exit code 0)
# send its combined output to sendmailtome.sh with subject "webwatch: pages changed".

# set -uvx

# Resolve script directory (so this script works when run from elsewhere)
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Change working directory to parent directory of the script (project root)
cd "$DIR/.." || exit 1
JAR="$DIR/../target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Set OPENAI_API_KEY if not already set
if [ -z "$OPENAI_API_KEY" ]; then
  export OPENAI_API_KEY=$(cat $HOME/.openai-api-key.txt)
fi

TMPOUT="$(mktemp)"
trap 'rm -f "$TMPOUT"' EXIT

java -jar "$JAR" --checkpages >"$TMPOUT" 2>&1
rc=$?

if [[ $rc -ne 0 ]]; then
  echo "Java call failed with exit code $rc, no email sent." >&2
  cat "$TMPOUT"
  exit $rc
fi

SENDMAIL_SCRIPT="$DIR/sendmailtome.sh"
cat "$TMPOUT" | "$SENDMAIL_SCRIPT" "webwatch: pages changed"
if [[ $? -ne 0 ]]; then
  echo "sendmailtome.sh failed, not updating seen pages file." >&2
  exit 1
fi
exit $?
