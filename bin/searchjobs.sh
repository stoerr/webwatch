#!/usr/bin/env bash
# Run the webwatch jar with --searchjobs; if it exits successfully, send its output
# to sendmailtome.sh with subject "searchjobs results". If the java call
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

java -jar "$JAR" --searchjobs >"$TMPOUT" 2>&1
rc=$?

if [[ $rc -ne 0 ]]; then
  if [[ $rc -eq 2 ]]; then
    echo "No search results (exit code 2), no email sent."
    exit $rc
  fi
  echo "Java call failed with exit code $rc, no email sent." >&2
  exit $rc
fi

SENDMAIL_SCRIPT="$DIR/sendmailtome.sh"

cat "$TMPOUT" | "$SENDMAIL_SCRIPT" "searchjobs results"
if [[ $? -ne 0 ]]; then
  echo "sendmailtome.sh failed." >&2
  exit 1
fi
exit $rc

