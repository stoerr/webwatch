#!/usr/bin/env bash
# Run the webwatch jar with --tango; if it exits successfully, send its output
# to sendmailtome.sh with subject "new tango concerts". If the java call
# exits non-zero, do nothing.

# set -uvx

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR/.." || exit 1
JAR="target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Set OPENAI_API_KEY if not already set
if [ -z "$OPENAI_API_KEY" ]; then
  export OPENAI_API_KEY=$(cat $HOME/.openai-api-key.txt)
fi

TMPOUT="$(mktemp)"
trap 'rm -f "$TMPOUT"' EXIT

java -jar "$JAR" --tango >"$TMPOUT" 2>&1
rc=$?

if [[ $rc -ne 0 ]]; then
  echo "Java call failed with exit code $rc, no email sent." >&2
  exit $rc
fi

SENDMAIL_SCRIPT="$DIR/sendmailtome.sh"

cat "$TMPOUT" | "$SENDMAIL_SCRIPT" "new tango concerts"
if [[ $? -ne 0 ]]; then
  echo "sendmailtome.sh failed, not updating seen concerts file." >&2
  exit 1
fi
mv -f data/tango-concerts-seen-new.json data/tango-concerts-seen.json
exit $rc
