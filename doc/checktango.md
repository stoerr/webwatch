# checktango — check Dresden tango calendar for new concerts

This document explains the `checktango` feature in this project: what the Java program does, the helper scripts in `bin/`, required configuration, and how to run or troubleshoot it.

Summary
- Purpose: periodically check the Dresden Tango calendar for newly announced concerts and notify the user by email when there are new items.
- Main pieces:
  - Java class: `net.stoerr.tools.PrintTangoConcerts` (entry point for the `--tango` run).
  - Scripts: `bin/checktango.sh` and `bin/sendmailtome.sh` to run the jar and deliver mail.
  - State files: `data/tango-concerts-seen.json` and `data/tango-concerts-seen-new.json`.

How it works (high level)
- `PrintTangoConcerts` downloads the HTML from https://www.dresden-tango.de/html/ifkalender.html.
- It sanitizes the HTML (using jsoup) by removing element attributes to reduce noisy DOM fields.
- It uses langchain4j/AiServices with an OpenAI chat model (configured via `OPENAI_API_KEY`) to parse the cleaned HTML and extract a list of concerts.
  - The model contract (via system messages in the code) requests a JSON-serializable `ConcertList` containing `Concert` objects (date, time, description, location, link).
  - The configured model name in the code is `gpt-4o-search-preview`.
- The program reads the previous known concerts from `data/tango-concerts-seen.json` (if present), compares to the newly extracted concerts, and computes only the new concerts.
- If there are new concerts, it prints them to stdout in a short human-readable form and writes the currently-seen concerts into `data/tango-concerts-seen-new.json`.
- The program exits with code 0 if new concerts were found (successful notification case), and exits with code 1 if there are no new concerts.
  - Any other non-zero exit (exceptions) will also prevent notification scripts from running.

Files used and produced
- `src/main/java/net/stoerr/tools/PrintTangoConcerts.java` — main implementation.
- `data/tango-concerts-seen.json` — JSON file containing the last known ConcertList. This is read at startup if present.
- `data/tango-concerts-seen-new.json` — Written at the end of a successful run. The `bin/checktango.sh` script moves/renames this file to `data/tango-concerts-seen.json` after sending the email.

Important environment/configuration
- OPENAI_API_KEY environment variable must be set for the OpenAI client used by langchain4j.
  - Example (bash):

    export OPENAI_API_KEY="sk_..."

- The `bin/sendmailtome.sh` script expects a configuration file at `$HOME/.secrets/.sendmailtome` with the following variables (simple key=value lines):
  - FROM — sender email address
  - TO — recipient email address
  - SMTP — SMTP server (host:port)
  - USER — SMTP username
  - PASS — SMTP password (careful with secrets)
  - TLS — optional, `yes` or `no` (defaults to `yes`)

  Example file (~/.secrets/.sendmailtome):

    FROM=me@example.com
    TO=me@example.com
    SMTP=smtp.example.com:587
    USER=me@example.com
    PASS='supersecret'
    TLS=yes

  - Note: `sendmailtome.sh` warns if file permissions are not `600`.
  - The script requires the `sendemail` command-line client to be installed and available in PATH (the small `sendemail` utility that can send SMTP mail).

Scripts in `bin/`
- `bin/checktango.sh`:
  - Resolves its own directory and expects the fat jar at `../target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar` relative to the script.
  - Runs `java -jar <jar> --tango` and captures combined stdout+stderr to a temporary file.
  - If the java process exits with non-zero status, the script exits with that status and does nothing further (no mail, no state update).
  - If java exits with 0, the script pipes the captured output to `bin/sendmailtome.sh` with the subject `new tango concerts`, then moves `data/tango-concerts-seen-new.json` to `data/tango-concerts-seen.json` to persist the new state.

- `bin/sendmailtome.sh`:
  - Reads its configuration from `$HOME/.secrets/.sendmailtome`.
  - Validates the presence of required variables and that the `sendemail` tool is installed.
  - Reads the email body from stdin, composes a sendemail command with the provided SMTP credentials and sends the mail.
  - Prints a masked summary (hiding the password) before sending.

How to run manually
- Build (locally) so the fat-jar is available under `target/` (the repo already contains the jar in `target/` in this workspace snapshot).
- Run the check once and print results to your terminal:

    java -jar target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar --tango

- To run the scripted flow (including email) from the repository root (assuming `bin/` is executable):

    bin/checktango.sh

  - Make sure `bin/sendmailtome.sh` is present and configured, and that `sendemail` is installed.

Exit codes and behavior (important for cron)
- Java exit code meanings (from `PrintTangoConcerts`):
  - 0 — New concerts were found and printed (success; `checktango.sh` will send mail).
  - 1 — No new concerts since last run (no mail will be sent).
  - Other non-zero — error occurred during run (no mail, check logs/temporary output captured by `checktango.sh`).
- `checktango.sh` forwards the java exit code to its caller if non-zero. If java exits 0, the script attempts to send email and returns the exit code from the mail-send step.

Security and privacy notes
- Keep your OpenAI API key and SMTP password secret. Do not commit `$HOME/.secrets/.sendmailtome` to version control.
- The `sendmailtome.sh` script recommends `chmod 600` on the config file to restrict permissions.
- The HTML fetched from the public calendar is sent to OpenAI (via langchain4j) for extraction — be mindful that you are sending that content to the model provider.

Troubleshooting
- "Jar not found" from `bin/checktango.sh` — build the project (maven) so the fat jar is present under `target/` or update the script to point to your jar location.
- `sendemail command not found` — install a simple sendmail client such as the `sendemail` utility (packaged on many systems) or adapt `sendmailtome.sh` to use `mailx`/`msmtp`/`swaks` or a small Python/Java helper.
- If no email is sent, inspect the temp capture file in `bin/checktango.sh` (the script itself uses a temp file and removes it on exit). For manual debugging, run the java command directly to see console output.
- If the program crashes with exceptions about the OpenAI client, ensure `OPENAI_API_KEY` is set in the environment where the script runs (cron/systemd unit, etc.).
