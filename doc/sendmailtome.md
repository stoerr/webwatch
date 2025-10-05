# sendmailtome.sh — send the captured output to your mailbox

This document describes the `bin/sendmailtome.sh` helper script and how to configure it.

Purpose
- A tiny wrapper that sends an email using a simple command-line SMTP client (`sendemail`).
- Intended to be used from other helper scripts in `bin/` (for example `bin/checktango.sh` or `bin/checkforchanges.sh`) to deliver captured program output when changes or noteworthy events occur.

Location
- `bin/sendmailtome.sh`

Configuration
- The script expects a configuration file at `$HOME/.secrets/.sendmailtome`.
- The file is a plain shell-style `KEY=value` file. Required variables:
  - FROM — sender email address (e.g. `me@example.com`)
  - TO — recipient email address
  - SMTP — SMTP host and port (`smtp.example.com:587`)
  - USER — SMTP username
  - PASS — SMTP password (may be quoted)
  - TLS — optional; `yes` or `no` (defaults to `yes` if omitted)

Example `~/.secrets/.sendmailtome`:

    FROM=me@example.com
    TO=me@example.com
    SMTP=smtp.example.com:587
    USER=me@example.com
    PASS='supersecret'
    TLS=yes

Security notes
- The script will warn if the config file's permissions are more permissive than `600`. Use `chmod 600 ~/.secrets/.sendmailtome` to restrict access.
- Do not commit this file to version control.

Dependencies
- The script uses the third-party command-line tool `sendemail` (a small SMTP client). Make sure it is installed and available in PATH.
  - If you prefer a different tool (`msmtp`, `swaks`, `mailx`, a short Python script), you can adapt `bin/sendmailtome.sh` accordingly.

Usage
- The script reads the email body from stdin and takes the subject as its positional arguments. Examples:

    # send a one-line message
    echo "Hello" | bin/sendmailtome.sh "Test subject"

    # send the captured output of another command
    some_command | bin/sendmailtome.sh "command completed"

Behavior and exit codes
- Exit code 1 — configuration file not found.
- Exit code 2 — usage error (no subject provided).
- Exit code 3 — `sendemail` command not found.
- On success, the script exits with whatever exit code the `sendemail` command returned (0 indicates success).

What the script prints
- Before sending it prints a masked summary of the mail to be sent (FROM/TO/SMTP/USER/TLS/Subject/Body size). The password is not printed.

Troubleshooting
- If the script says `sendemail command not found`, install a small SMTP client or change the script to call your preferred mailer.
- If mail delivery fails, run the sendemail command shown in the script manually with the provided config to inspect SMTP-level errors.

Notes for automation
- This helper is intentionally minimal: it sources the config and then invokes the external mail client. It does not attempt to manage retries or store credentials securely beyond the filesystem permissions advice above.
- Use the script from cron or other schedulers, making sure the environment (notably PATH and HOME) is what you expect. If the job runs as another user, place the config under that user's home directory.

Contact / maintenance
- The script is intentionally small and readable; if you need TLS certificate control, OAuth-based SMTP, or advanced envelope features, consider replacing this helper with a small Java/Python utility or a dedicated mail-sending tool.

(End of `sendmailtome` documentation)

