# Repository Guidelines
This guide keeps Zuul Consul contributions consistent. Review it before touching filters, routing logic, or deployment artifacts.

## Project Structure & Module Organization
`app/` hosts the Java 21 server (`src/main/java/org/jadetipi/zuulconsul/**`) plus configuration in `src/main/resources`. `filters/` contains reusable Groovy 5 filters split into `inbound/` and `outbound/`. Root `build.gradle` wires shared plugins, while `gradle/scripts/*.gradle` provide repository, tagging, and integration-test helpers. Add tests under each module’s `src/test` tree and keep assets such as sample configs in `app/src/main/resources`.

## Build, Test, and Development Commands
- `./gradlew clean build` – compiles all modules, runs unit tests, and assembles jars.
- `./gradlew :app:run` – launches the gateway locally; export `ZUUL_*` vars (see `README.md`) to reach Consul.
- `./gradlew :app:fatJar && java -jar app/build/libs/zuul-consul-all.jar` – builds and executes the systemd/Docker artifact.
- `./gradlew :filters:build` – validates Groovy filter changes in isolation.
- `./gradlew test` or `./gradlew :app:integrationTest` (when `src/integrationTest` exists) – run fast feedback suites before opening a PR.

## Coding Style & Naming Conventions
Stick to 4-space indentation, `UpperCamelCase` classes, and `lowerCamelCase` members. Constants and context keys belong in `static final` `UPPER_SNAKE_CASE`, mirroring `ConsulRoutingFilter`. Use `org.jadetipi.zuulconsul.*` packages for Java code, apply `@Slf4j` in Groovy filters, and keep filters stateless with `SomethingFilter.groovy` naming. Java origins/utilities live under feature folders such as `origins/` or `consul/`.

## Testing Guidelines
Spock (Groovy) and JUnit 5 (Java) are available by default. Create specs/tests under `src/test/groovy` or `src/test/java` with `*Spec`/`*Test` suffixes, focusing on URI parsing, Consul cache behavior, and filter context state. Mock Consul responses with Mockito or Spock stubs instead of hitting live agents. When adding new environments or config parsing, extend `src/integrationTest` and ensure `./gradlew test` and, if applicable, `./gradlew :app:integrationTest` pass locally.

## Commit & Pull Request Guidelines
Follow an imperative, module-scoped summary such as `app: tighten Consul cache metrics`. Reference related issues (`GH-123`) in the body and describe config or env-var changes explicitly. Every PR should include purpose/impact, testing evidence (`./gradlew test` output or screenshots for filter responses), and rollout notes (e.g., default tag updates). Squash local fixups before review unless multiple commits clarify the story.

## Security & Configuration Tips
Never commit Consul tokens or `.env` contents; rely on `rootProject.ext.loadEnvFile` for local secrets. Document new environment variables in `README.md` and provide safe defaults in `app/src/main/resources/application.properties`. When testing routing against production-like services, prefer read-only Consul ACLs and scope `ZUUL_REACHABLE_ENVIRONMENTS` so local runs cannot hit unintended clusters.
