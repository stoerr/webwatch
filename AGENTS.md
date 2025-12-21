# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/net/stoerr/tools/`: CLI tools (entry point is `App`).
- `src/test/java/net/stoerr/tools/`: JUnit tests and ad-hoc integration tests.
- `src/main/resources/`: runtime resources if needed.
- `bin/`: helper scripts for running checks and sending mail (`checkforchanges.sh`, `checktango.sh`, `sendmailtome.sh`).
- `data/`: runtime state and configuration (e.g., `data/checkpages-config.json`).
- `doc/`: feature documentation (`doc/checkpages.md`, `doc/checktango.md`, `doc/sendmailtome.md`).

## Build, Test, and Development Commands
- `mvn package`: build the fat JAR (creates `target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar`).
- `mvn test`: run JUnit tests (uses Surefire).
- `java -jar target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar --checkpages`: run the page checker.
- `java -jar target/webwatch-1.0-SNAPSHOT-jar-with-dependencies.jar --tango`: run the tango extractor.
- `bin/checkforchanges.sh`: wrapper that emails when pages change.
- `bin/checktango.sh`: wrapper that emails when new concerts appear.

## Coding Style & Naming Conventions
- Java 17, 4-space indentation, braces on the same line.
- Classes use `UpperCamelCase`; methods and variables use `lowerCamelCase`.
- Keep CLI entry points in `net.stoerr.tools` and route new commands via `App`.
- No formatter is configured; keep diffs small and consistent with existing style.

## Testing Guidelines
- Framework: JUnit 5 (Jupiter).
- Test classes use `*Test` naming under `src/test/java`.
- Some tests are intentionally `@Disabled` (e.g., OpenAI integration). Document any new disabled tests.
- Run `mvn test` before PRs that touch logic.

## Commit & Pull Request Guidelines
- Commit messages in this repo are short, lowercase, and imperative (e.g., `add job`).
- PRs should include: purpose, how to run/verify, and any config or data changes.
- If a PR changes scripts or cron usage, include a brief note on expected environment variables.

## Security & Configuration Tips
- Do not commit secrets. SMTP config lives in `$HOME/.secrets/.sendmailtome`.
- The tango feature requires `OPENAI_API_KEY`; keep it in the environment only.
