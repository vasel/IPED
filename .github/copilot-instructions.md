# Copilot instructions (IPED)

## Big picture
- IPED is a multi-module Java 11 Maven build (root aggregator in `pom.xml`) producing a runnable “release” layout under `target/release/iped-<version>/`.
- Keep module boundaries: `iped-api` defines shared interfaces (many are auto-generated / not fully documented), while implementation-heavy code lives in `iped-engine`, `iped-parsers-impl`, `iped-viewers-impl`, `iped-carvers-impl`.

## Repo layout (where to look)
- `iped-app/`: desktop application + packaging; bundles extra tools/JRE during `package` (see `iped-app/pom.xml`). UI is Swing-based with CLI wiring via JCommander.
- `iped-engine/`: core processing + web API endpoints (JAX-RS + Swagger annotations). Example REST resource: `iped-engine/src/main/java/iped/engine/webapi/Graph.java`.
- `iped-parsers/`, `iped-viewers/`, `iped-carvers/`: parent modules; implementations typically in `*-impl` submodules.
- `iped-utils/`: shared utilities + resources.
- `iped-app/resources/config/`: default config templates; `iped-app/resources/localization/`: i18n properties; `iped-app/resources/plugins/`: plugin payloads.

## Build & test (Windows-friendly)
- Prereqs: JDK 11 **with JavaFX** (README suggests “Liberica OpenJDK 11 Full”), plus Maven.
- Helper script: run `set_env.bat` to set `JAVA_HOME`/`MAVEN_HOME` (and proxies if needed).
- Full build: `mvn clean install` (creates snapshot release under `target/release/`).
- Build a single module (and deps): `mvn -pl iped-engine -am -DskipTests compile`.
- Run tests for a module: `mvn -pl iped-engine test` (JUnit 4 + Hamcrest are the baseline).

## Project-specific coding patterns
- Web API resources use JAX-RS annotations (`@Path`, `@GET`, `@POST`) plus Swagger (`@Api`, `@ApiOperation`) in `iped-engine/src/main/java/iped/engine/webapi/`.
- Graph feature uses Neo4j and listener-style callbacks in `iped-engine/src/main/java/iped/engine/graph/`.
  - Don’t assume listeners return booleans; e.g. `LabelQueryListener#labelFound(String)` and `ConnectionQueryListener#connectionsFound(String,int)` are **void** callbacks.
- Optional/“provided” integrations exist in `iped-engine` (e.g., Azure/Google speech SDKs). Avoid forcing runtime requirements when only compile-time APIs are needed.

## When making changes
- Stay compatible with Java 11 APIs (no newer language/library features).
- If you change an interface in `iped-engine/.../graph` or `iped-api`, search for cross-module implementers (e.g., `iped-app/src/main/java/iped/app/graph/*`) and update them in the same PR.
