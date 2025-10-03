# checkpages — monitor web pages for changes

This document describes the `CheckWebPagesForChanges` application that is included in this project.

Purpose
- Monitor a list of web pages and detect changes in specific parts of each page (defined by CSS selectors).
- Keep lightweight, versioned captures of extracted content for each page in `data/checkpages/`.
- Provide concise, human-readable change output that can be used by scripts (e.g., to send notifications).

Main pieces
- Java class: `net.stoerr.tools.CheckWebPagesForChanges` (located next to `PrintTangoConcerts`).
- Config file (JSON): `data/checkpages-config.json` (default location). You can pass an alternate config path as the first CLI argument.
- Data directory: `data/checkpages/` — contains per-page JSON captures and `.prev.json` backup copies.

How it works (high level)
- The program reads an array of page entries from the config file. Each entry may contain the fields:
  - `name` (optional): a short identifier used for the stored filename.
  - `url` (required): the page URL to fetch.
  - `selectors` (optional): an array of CSS selectors to extract. If omitted, `body` is used.
- For each page the program:
  - Fetches the HTML via HTTP(S).
  - Parses the HTML with jsoup and removes element attributes to reduce noisy DOM differences.
  - For each configured selector it extracts the matching elements (outer HTML) and stores them as content.
  - Writes a JSON capture to `data/checkpages/<sanitized-name>.json`. If a previous capture existed, it is copied to `*.prev.json` before overwriting.
  - Compares the new capture to the previous one and prints a short summary of added/changed/removed selectors and short previews of the content.

Files read and written
- Input/configuration
  - `data/checkpages-config.json` — JSON array of page configs (default). Can be overridden via CLI argument.

- Data produced
  - `data/checkpages/<sanitized>.json` — current capture for each page.
  - `data/checkpages/<sanitized>.prev.json` — previous capture (kept as a backup when the current one is updated).

Exit codes
- 0 — at least one page showed changes (success: you can use this to trigger notifications).
- 1 — no changes detected.
- 2 — fatal error (e.g. missing/unreadable config).

Example configuration (`data/checkpages-config.json`)

```json
[
  {
    "name": "dresden-tango-calendar",
    "url": "https://www.dresden-tango.de/html/ifkalender.html",
    "selectors": ["#content", "table"]
  }
]
```

Running the tool
- From the project root (jar already packaged in `target/` in this workspace snapshot):

```bash
# run the class directly from the fat-jar
java -cp target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar net.stoerr.tools.CheckWebPagesForChanges

# or with a specific config file
java -cp target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar net.stoerr.tools.CheckWebPagesForChanges data/my-checkpages.json
```

Notes and tips
- The program uses jsoup and Gson — the project already depends on these libraries.
- Filenames are sanitized deterministically (host + path rewritten) to keep names filesystem-safe and reasonably short. If the `name` field is present it is preferred for the filename.
- The tool intentionally strips element attributes (IDs, classes) to reduce noisy diffs; change this behavior in the code if you need attribute-aware diffs.
- For automation (cron), run the command and use the exit code to decide whether to notify. You can wrap it similar to `bin/checktango.sh` to capture output and send an email when exit code is 0.

Security
- Keep any credentials for notification scripts out of the repo. The tool itself does not require credentials to run but will perform outbound HTTP(S) fetches.

Troubleshooting
- If a page fails to fetch, the program prints an error and continues with other pages.
- If an extraction produces empty results for selectors, the capture will contain an empty string for that selector; this is treated as a valid state and will be compared to previous runs.

"Why separate config and capture files?"
- The config contains metadata and extraction instructions.
- The capture files are structured, versionable artifacts that make diffing and historic inspection easy without mingling metadata and content.

