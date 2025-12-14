searchjobs — specification

Purpose

Add a small "search jobs" feature to the webwatch project: a CLI entrypoint that reads search jobs from
`src/main/resources/searchjobs.json`, sends each job prompt to the model `gpt-5-search-api` using langchain4j,
and when a job returns anything other than the literal string "NOT FOUND" the program prints the result and
exits successfully so a wrapper script can email the result to the maintainer.

Quick summary (what the feature provides)

- `bin/searchjobs.sh`: shell starter that runs the fat jar with `--searchjobs`. If the Java process exits
  zero it pipes the output to `bin/sendmailtome.sh` (like `bin/checktango.sh`).
- `App` will support the `--searchjobs` flag and start `SearchJobs.main`.
- `SearchJobs` reads `src/main/resources/searchjobs.json` and runs each job prompt against the model
  `gpt-5-search-api` via langchain4j. If a job returns anything other than `"NOT FOUND"` that text is
  printed and treated as a positive result.

Goals and constraints

- Keep behavior consistent with existing scripts (`bin/checktango.sh`).
- No live API calls in tests; tests should mock the LLM layer.
- Keep exit-code semantics consistent with other tools: non-zero means "nothing to send" or failure.
- The project already contains a sample `searchjobs.json`; use the same minimal schema but allow optional
  fields for convenience.

Files to create / modify (high level)

- New: `bin/searchjobs.sh` — shell starter modeled on `bin/checktango.sh`.
- Modify: `src/main/java/net/stoerr/tools/App.java` — add `--searchjobs` flag handling.
- New: `src/main/java/net/stoerr/tools/SearchJobs.java` — main implementation.
- Modify: `src/main/resources/searchjobs.json` — can contain one or more jobs; a sample already exists.
- New tests: unit tests for JSON parsing and logic, with mocked LLM.
- New docs: this file `doc/searchjobs.md`.

Runtime environment

- The wrapper script will follow the same environment convention as `bin/checktango.sh`, i.e. it will set
  `OPENAI_API_KEY` from `$HOME/.openai-api-key.txt` when not present. The program itself expects the
  environment to be configured (the code can assume an API key is available; let it fail otherwise).

searchjobs.json schema

The configuration is a JSON array of job objects. Minimal fields are `title` and `prompt`. Additional
optional fields are supported for convenience.

Schema (informal):

- id: string (optional) — unique identifier for the job; if absent a sanitized form of `title` can be used.
- title: string (required) — short human title shown in output and used for the email subject when present.
- prompt: string (required) — prompt text to be fed to the model; the prompt must instruct the model to
  return exactly either the single token `NOT FOUND` (when nothing matches) or the result content.
- emailSubject: string (optional) — subject to use when sending mail for this job; fall back to
  `new search job: <title>` if absent.
- seenFile: string (optional) — path under `data/` used to persist "seen" results. If absent a default
  `data/searchjobs-<id|-sanitized-title>-seen.json` will be used.

Example (the project already includes the following example in `src/main/resources/searchjobs.json`):

[
  {
    "title": "Milonga-Kurse",
    "prompt": "Pr\u00fcfe ob in der n\u00e4chsten Zeit ein Milonga Wochenendkurs in Dresden angeboten wird. Ich meine keine Tanz-Veranstaltungen, sondern den Tanz \"Milonga\", den man bei Kursen zum Tango Argentino lernt. Einen allgemeinen Tango-Kurs n\u00fctzt uns nichts - da haben wir bereits viele Kurse besucht. Fortlaufende Kurse schaffen wir nicht. Nur ein Wochenend-Kurs (Workshop) w\u00fcrde uns helfen. Keine Festivals, keine Events. Wenn Du keine findest, dann gib nur eine \"NOT FOUND\" aus, ansonsten eine Liste mit Kursbeschreibungen. Bitte keine Instruktionen, Hinweise etc. - nur Wochenend-Workshops mit Exclusiv-Thema \"Milonga\" die Du findest, oder \"NOT FOUND\"."
  }
]

Behavior details

1) Startup / script

- `bin/searchjobs.sh` should mirror `bin/checktango.sh`:
  - cd to project root, set `JAR` to `target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar`.
  - ensure `OPENAI_API_KEY` is set (the script may read it from `$HOME/.openai-api-key.txt` when missing, like
    existing scripts do).
  - create a temp file `TMPOUT` and trap its removal on EXIT.
  - run `java -jar "$JAR" --searchjobs >"$TMPOUT" 2>&1` and capture the exit code.
  - if the java call fails (exit code non-zero), do not call `sendmailtome.sh` and return that exit code.
  - if the java call succeeds, pipe `TMPOUT` into `bin/sendmailtome.sh` with an appropriate subject and, on
    success, move any generated `data/*-seen-new.json` to their stable `*-seen.json` counterpart.

2) `App` integration

- `App.main` should inspect `args` and, when it sees `--searchjobs`, call `SearchJobs.main(args)` (or a
  dedicated entry method). Keep the same CLI parsing style used for `--tango`.

3) `SearchJobs` runtime flow (Java)

- Read `src/main/resources/searchjobs.json` as classpath resource and parse it to a list of Job objects using
  Gson (project already uses Gson elsewhere).
- For each job:
  - build a ChatModel via langchain4j. Use the model name `gpt-5-search-api`. Example of the pattern used in
    this project:

    OpenAiChatModel chatModel = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-5-search-api")
        .build();

    // Use AiServices to define a typed extractor interface for the prompt
    SearchExtractor services = AiServices.builder(SearchExtractor.class)
        .chatModel(chatModel)
        .build();

    Where `SearchExtractor` is a small interface with one method like:

    interface SearchExtractor {
        String search(String prompt);
    }

  - Call `services.search(job.prompt)` and trim the response.
  - If the response equals (exact match) the string `NOT FOUND` (case-sensitive), treat as no result.
  - Otherwise treat the response as a positive result and print a clearly delimited output section
    including the job `title`, `id` (if present), and the response body.
- If any job returns a positive result, exit 0. If all jobs return `NOT FOUND`, exit with non-zero (e.g. 2)
  so the wrapper script can skip emailing.

4) Output format

- The program should print only the positive result content and a short header suitable for emailing. Avoid
  verbose debugging output on stdout — use stderr for logs if necessary. Example positive output:

  === SearchJobs result: Milonga-Kurse ===
  <model-output>

- If multiple jobs produce positive results, concatenate the sections in the same output stream.

5) Seen-file policy

- When a positive result is found, `SearchJobs` should write a "new" seen file for the job at the path
  `data/<seenFile>-new.json` (for example `data/searchjobs-milonga-kurse-seen-new.json`). The shell script
  moves that file into place (rename to remove `-new`) only after `sendmailtome.sh` succeeds. This matches
  the pattern used by `bin/checktango.sh`.

6) Exit codes

- 0: One or more positive results were printed and everything completed normally.
- 1: Unexpected error (I/O, JSON parse error, model error).
- 2: No job returned a result (all returned `NOT FOUND`) — no email should be sent.

Developer notes and implementation details

- Use Gson for parsing `searchjobs.json` (consistent with the project).
- Use langchain4j OpenAI chat model builder and AiServices typed extractor pattern (see `PrintTangoConcerts` and
  existing project code for examples) to keep prompts and result extraction typed and testable.
- Do not attempt to defensively handle `null` API keys or model objects; follow the project convention and let the
  program fail if mandatory components are missing.
- When writing seen files, write to `*-new.json` first and let the caller move to the stable name only on
  successful email delivery.

Testing

- Unit tests should validate parsing of `searchjobs.json` and the job object defaults.
- Logic tests should mock the `SearchExtractor` (or the AiServices builder) to return controlled strings
  (`NOT FOUND` and a non-empty payload) to validate exit codes, output, and seen-file creation.
- Do not make live calls to OpenAI in unit tests.

Operational notes

- Cron usage: install a cron job that runs `bin/searchjobs.sh` regularly. The script follows the same behavior
  as `bin/checktango.sh` in returning a non-zero code on failures so cron/monitoring can detect issues.
- Mail behavior: the script will call `bin/sendmailtome.sh` with the subject supplied by the job (or a default).

Open questions for you (pick a choice before implementation)

- Per-job emails vs aggregated: should the wrapper send one mail per positive job, or one aggregated mail
  containing all positive results? The spec above assumes aggregated output and a single mail per run.
- Seen-file naming: the spec suggests one seen file per job. Confirm if you prefer a single aggregated seen file
  instead.

End of specification

