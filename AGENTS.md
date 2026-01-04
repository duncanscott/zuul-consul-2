# Repository Guidelines

## Project Structure & Module Organization
- `app/src/main/java/org/jadetipi/zuulconsul/**` holds the Java 21 gateway plus feature folders such as `origins/` and `consul/`; configuration lives in `app/src/main/resources`.
- Groovy 5 filters sit in `filters/inbound/` and `filters/outbound/` with shared utilities beside them; keep runtime assets (sample configs, templates) under `app/src/main/resources`.
- Tests mirror their modules: `src/test/java` or `src/test/groovy`, with optional `src/integrationTest` trees for slow suites. Build logic and helper scripts live in root `build.gradle` and `gradle/scripts/*.gradle`.

## Build, Test, and Development Commands
- `./gradlew clean build` – compiles every module, runs unit tests, and produces jars.
- `./gradlew :app:run` – launches the Zuul gateway locally; export `ZUUL_*` vars per `README.md` to hit Consul.
- `./gradlew :app:fatJar && java -jar app/build/libs/zuul-consul-all.jar` – assembles and runs the deployable artifact (systemd/Docker).
- `./gradlew :filters:build` – validates Groovy filters independently.
- `./gradlew test` or `./gradlew :app:integrationTest` – executes the fast or integration suites.

## Coding Style & Naming Conventions
- Default to 4-space indentation, `UpperCamelCase` classes, `lowerCamelCase` members, and `UPPER_SNAKE_CASE` constants/context keys.
- Java packages stay under `org.jadetipi.zuulconsul.*`; Groovy filter classes use `SomethingFilter.groovy` and remain stateless with `@Slf4j`.
- Keep configuration keys in `application.properties` and document new env vars alongside their defaults.

## Testing Guidelines
- Prefer JUnit 5 for Java and Spock for Groovy. Name specs/tests with `*Spec` or `*Test` and colocate in the matching module.
- Mock Consul interactions via Mockito/Spock stubs; avoid live ACLs. Extend `src/integrationTest` when adding new environments or config parsing.
- Run `./gradlew test` before every PR and `./gradlew :app:integrationTest` when integration fixtures exist.

## Commit & Pull Request Guidelines
- Format commit subjects as `module: imperative summary` (e.g., `app: tighten Consul cache metrics`) and reference issues such as `GH-123` in the body.
- PRs must describe purpose/impact, detail configuration or env-var changes, include test evidence (command output, screenshots), and mention rollout notes like default tag updates.
- Squash fixups locally unless multiple commits clarify the narrative.

## Security & Configuration Tips
- Never commit Consul tokens or `.env`; rely on `rootProject.ext.loadEnvFile` for local secrets.
- When exercising routing against shared clusters, constrain `ZUUL_REACHABLE_ENVIRONMENTS` and prefer read-only ACLs to prevent accidental writes.
