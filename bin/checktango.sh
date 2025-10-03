#!/usr/bin/env bash
# Run the webwatch jar with --tango; if it exits successfully, send its output
# to sendmailtome.sh with subject "new tango concerts". If the java call
# exits non-zero, do nothing.

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
java -jar "$JAR" --tango >"$TMPOUT" 2>&1
rc=$?

if [[ $rc -ne 0 ]]; then
  # Do nothing on failure (exit with the same code so callers can observe it)
  exit $rc
fi

# Path to sendmailtome.sh (located next to this script)
SENDMAIL_SCRIPT="$DIR/sendmailtome.sh"

if [[ ! -f "$SENDMAIL_SCRIPT" ]]; then
  echo "sendmailtome.sh not found in $DIR" >&2
  exit 1
fi

# Pipe the captured output to sendmailtome.sh with the requested subject.
# Use bash to execute the script if it's not executable.
if [[ -x "$SENDMAIL_SCRIPT" ]]; then
  cat "$TMPOUT" | "$SENDMAIL_SCRIPT" "new tango concerts"
  exit $?
else
  cat "$TMPOUT" | bash "$SENDMAIL_SCRIPT" "new tango concerts"
  exit $?
fi
