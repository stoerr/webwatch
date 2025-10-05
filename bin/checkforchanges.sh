#!/usr/bin/env bash
# Run the webwatch jar with --checkpages; if it exits successfully (exit code 0)
# send its combined output to sendmailtome.sh with subject "webwatch: pages changed".

set -u

# Resolve script directory (so this script works when run from elsewhere)
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$DIR/../target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar"

if [[ ! -f "$JAR" ]]; then
  echo "Jar not found: $JAR" >&2
  exit 1
fi

# Use a temp file to capture combined stdout+stderr (safer for large output)
TMPOUT="$(mktemp)"
trap 'rm -f "$TMPOUT"' EXIT

# Run java and capture combined stdout+stderr into the temp file
java -jar "$JAR" --checkpages >"$TMPOUT" 2>&1
rc=$?

if [[ $rc -ne 0 ]]; then
  # Do nothing on non-zero exit (exit with the same code so callers can observe it)
  exit $rc
fi

# Path to sendmailtome.sh (located next to this script)
SENDMAIL_SCRIPT="$DIR/sendmailtome.sh"

if [[ ! -f "$SENDMAIL_SCRIPT" ]]; then
  echo "sendmailtome.sh not found in $DIR" >&2
  exit 1
fi

# Pipe the captured output to sendmailtome.sh with the requested subject.
cat "$TMPOUT" | "$SENDMAIL_SCRIPT" "webwatch: pages changed"
exit $?

