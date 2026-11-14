# Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every production change follows `superpowers:test-driven-development` and `CLAUDE.md`. Reviews follow the review protocol in `CLAUDE.md`: `functional-reviewer` and `code-reviewer` after every task, plus `risk-reviewer` on the whole plan at the end.

**Goal:** Every later commit is born inside the hexagonal modules and already passes the architecture, formatting and coverage gates, locally and in CI.

**Architecture:** Six empty Gradle modules (`domain`, `application`, `adapters:providers`, `adapters:persistence`, `adapters:web`, `bootstrap`) whose `build.gradle.kts` dependencies encode the hexagon's direction, plus an `architecture-tests` module holding ArchUnit rules that check what Gradle cannot (ports only, no framework in the domain). Formatting and coverage are enforced by `./gradlew build` itself, so CI only runs the build.

**Tech Stack:** Gradle 9.9.1 (Kotlin DSL, `buildSrc` convention plugin, version catalog), Java 25, Spotless 8.10.4 with google-java-format 1.37.0, ArchUnit 1.5.1 (`archunit-junit5`), JaCoCo 0.8.16 with Gradle's `jacoco-report-aggregation` plugin, GitHub Actions, Dependabot.

**Spec:** [design spec](../design-spec.md) — §6 (modules and ports), §6.1 (version policy), §6.11 (CI). Roadmap: [plan 1](../roadmap.md).

## Global Constraints

- Versions: latest stable GA, no release candidates, no recent `.0` releases, verified at the primary source (§6.1). Pinned here: Spotless 8.10.4, google-java-format 1.37.0 (2.0.0 is a two-week-old major), ArchUnit 1.5.1, JaCoCo 0.8.16, `actions/checkout` v7.0.1, `actions/setup-java` v6.1.0 (v6.2.0 and v6.3.0 are under 14 days old), `gradle/actions` v6.4.1, `actions/upload-artifact` v7.0.2. Actions are pinned by commit SHA with the version in a trailing comment.
- Package root `com.flightaggregator`; ports in `application.port.in` and `application.port.out`, services in `application.service`, adapters in `adapter.provider`, `adapter.persistence`, `adapter.web`, wiring in `bootstrap` (§6, "Ports").
- Coverage gate: **90% of lines and 80% of branches**, over all backend modules together; excluded: the Spring Boot entry point, configuration classes and generated code (§6.11).
- Formatting: google-java-format (Google Java Style, 2-space indentation, 100 columns); `spotlessCheck` runs inside `./gradlew build`; the existing code is reformatted once, in a commit of its own (§6.11).
- Dependabot: weekly, grouped per ecosystem, **14-day cooldown** (§6.11).
- `.gitattributes` keeps LF everywhere except `*.bat` / `*.cmd`; nothing in this plan may introduce CRLF.
- Configuration cache and build cache stay enabled (`gradle.properties`); every task added must be compatible with them.
- English everywhere; no AI attribution in commits; push only after `gh api user --jq .login` prints `dromero001` (`CLAUDE.md`).

## Review Focus

1. **Vacuous architecture rules:** a rule over a package that has no classes passes silently, and a typo in a package name would turn a rule off. Each rule gets a meta-test that runs it against a fixture violating it and asserts a violation (Task 3).
2. **Coverage exclusions that hide real code:** a broad pattern such as `**/adapter/**` would make the gate meaningless. The exclusions are an explicit list, and Task 4 asserts that `mock-gds` classes appear in the aggregated report.
3. **The gate that never fails:** a coverage rule wired to the wrong task passes forever. Task 4 proves the gate fails by running it with a minimum the code cannot reach.
4. **Windows checkout:** Spotless must read line endings from `.gitattributes`, so that a checkout on Windows does not report every file as unformatted (Task 1, `lineEndings` left at its `GIT_ATTRIBUTES` default and checked).
5. **CI drift from local:** CI must run exactly `./gradlew build` on Java 25, the same command `CLAUDE.md` requires before a commit, so a green local build means a green CI (Task 5).

---

### Task 1: Spotless with google-java-format, and the one-off reformat

**Files:**
- Modify: `gradle/libs.versions.toml` (versions `spotless = "8.10.4"`, `google-java-format = "1.37.0"`; library `spotless-gradle-plugin = com.diffplug.spotless:spotless-plugin-gradle`)
- Modify: `buildSrc/build.gradle.kts` (add `mavenCentral()` and `implementation(libs.spotless.gradle.plugin)`)
- Modify: `buildSrc/src/main/kotlin/flightaggregator.java-conventions.gradle.kts` (apply `com.diffplug.spotless`; `java { googleJavaFormat(<version from catalog>); target("src/**/*.java") }`)
- Modify: `CLAUDE.md` ("Before every commit": run `./gradlew spotlessApply` when `spotlessCheck` fails)
- Reformatted: every `*.java` under `mock-gds/`

**Interfaces:**
- Produces: every module applying `flightaggregator.java-conventions` gets `spotlessCheck` (wired into `check`) and `spotlessApply`.

Commits stay green (`CLAUDE.md`), so the reformat lands **before** the gate: first a commit with only the reformatted code (the build has no `spotlessCheck` yet), then the commit that adds the gate.

- [ ] **Step 1: Configure Spotless in the convention plugin** as listed above.
- [ ] **Step 2: Run the check and watch it fail on the existing 4-space code**
  Run: `./gradlew spotlessCheck`
  Expected: FAIL, listing `mock-gds` files with indentation violations.
- [ ] **Step 3: Reformat**
  Run: `./gradlew spotlessApply`, then `./gradlew build`
  Expected: BUILD SUCCESSFUL, 79 tests, 0 failures; the diff of the `*.java` files changes only whitespace and line wrapping.
- [ ] **Step 4: Check line endings stay LF:** `git ls-files --eol mock-gds | grep -v 'i/lf'` prints nothing.
- [ ] **Step 5: Commit the reformatted code alone** (stage only `mock-gds/**/*.java`; the build without the Spotless configuration is green):
  `git commit -m "Reformat the existing code to Google Java Style"`
- [ ] **Step 6: Commit the gate** (catalog, `buildSrc`, convention plugin, `CLAUDE.md`), run `./gradlew build` green, push both commits:
  `git commit -m "Format Java with google-java-format through Spotless"`

### Task 2: Hexagonal module skeleton

**Files:**
- Modify: `settings.gradle.kts` (include `domain`, `application`, `adapters:providers`, `adapters:persistence`, `adapters:web`, `bootstrap`; replace the "added when its first task starts" comment)
- Create: `domain/build.gradle.kts` (conventions only, no dependencies)
- Create: `application/build.gradle.kts` (`api(project(":domain"))`)
- Create: `adapters/providers/build.gradle.kts`, `adapters/persistence/build.gradle.kts`, `adapters/web/build.gradle.kts` (each `implementation(project(":application"))`, nothing else)
- Create: `bootstrap/build.gradle.kts` (`implementation` of the three adapters and `application`)
- Create: one `package-info.java` per package root, each with a one-sentence Javadoc stating the package's role: `com.flightaggregator.domain`, `com.flightaggregator.application.port.in`, `com.flightaggregator.application.port.out`, `com.flightaggregator.application.service`, `com.flightaggregator.adapter.provider`, `com.flightaggregator.adapter.persistence`, `com.flightaggregator.adapter.web`, `com.flightaggregator.bootstrap`
- Modify: `docs/architecture/README.md` (a Mermaid diagram of the modules and their allowed dependencies, and the port rules in one list)

**Interfaces:**
- Produces: Gradle paths `:domain`, `:application`, `:adapters:providers`, `:adapters:persistence`, `:adapters:web`, `:bootstrap`; the package roots above. Spring Boot is **not** added here; plan 4 adds it to `bootstrap` when there is something to wire.

- [ ] **Step 1: Write the failing check:** `./gradlew :domain:build :adapters:web:build`
  Expected: FAIL, "project 'domain' not found".
- [ ] **Step 2: Create the modules and package roots** as listed.
- [ ] **Step 3: Prove Gradle enforces the direction:** add a throwaway `import com.flightaggregator.adapter.web.*;`-style reference from a class in `domain` to any adapter type, run `./gradlew :domain:compileJava`, expect a compilation FAIL; delete the throwaway class.
- [ ] **Step 4: Run the full build**
  Run: `./gradlew build` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Add the architecture diagram, then commit and push**
  `git commit -m "Add the hexagonal module skeleton"`

### Task 3: ArchUnit rules with meta-tests

**Files:**
- Create: `architecture-tests/build.gradle.kts` (conventions; `testImplementation` of every module from Task 2 and of `archunit-junit5`; add `archunit = "1.5.1"` and the library to the catalog; for the Spring-allowed fixture, `testImplementation(platform(spring-boot-dependencies))` with `spring-boot = "4.1.2"` in the catalog, and `testImplementation("org.springframework:spring-context")`)
- Create: `architecture-tests/src/test/java/com/flightaggregator/architecture/HexagonalRules.java`
- Create: `architecture-tests/src/test/java/com/flightaggregator/architecture/HexagonalArchitectureTest.java`
- Create: `architecture-tests/src/test/java/com/flightaggregator/architecture/HexagonalRulesTest.java`
- Create: fixtures under `architecture-tests/src/test/java/com/flightaggregator/architecture/fixture/<case>/…`, one tiny package tree per violation
- Modify: `settings.gradle.kts` (include `architecture-tests`)

**Interfaces:**
- Produces: `final class HexagonalRules` with one `static ArchRule <name>(String rootPackage)` per rule, and `static List<ArchRule> forRoot(String rootPackage)` returning all five in this order:
  1. `domainDependsOnlyOnTheJdk` — classes in `<root>.domain..` depend only on `java..` and `<root>.domain..`
  2. `applicationDependsOnlyOnTheDomainAndSpring` — `<root>.application..` depends only on `java..`, `org.springframework..`, `<root>.domain..` and `<root>.application..` (Spring is allowed in the application layer, spec §6 "Ports")
  3. `adaptersUseOnlyPorts` — `<root>.adapter..` do not depend on `<root>.application.service..`
  4. `adaptersAreIndependent` — the slices `<root>.adapter.(*)..` do not depend on each other
  5. `nothingDependsOnBootstrap` — no class outside `<root>.bootstrap..` depends on `<root>.bootstrap..`
  The rules keep ArchUnit's default `failOnEmptyShould`, so a rule over a mistyped package fails. Only `HexagonalArchitectureTest`, which evaluates `forRoot("com.flightaggregator")` against the production classes, calls `.allowEmptyShould(true)`, because the modules hold nothing but `package-info` until plan 2; a comment says so and names plan 2 as the point where it is removed.

- [ ] **Step 1: Write the first failing meta-test**
  ```java
  @Test
  void domainDependingOnAnAdapterIsAViolation() {
    JavaClasses classes = new ClassFileImporter()
        .importPackages("com.flightaggregator.architecture.fixture.domainleak");
    EvaluationResult result = HexagonalRules.domainDependsOnlyOnTheJdk(
        "com.flightaggregator.architecture.fixture.domainleak").evaluate(classes);
    assertThat(result.hasViolation()).isTrue();
  }
  ```
  with fixture `fixture/domainleak/domain/Leaky.java` referencing `fixture/domainleak/adapter/web/Controller.java`.
- [ ] **Step 2: Run it and watch it fail:** `./gradlew :architecture-tests:test --tests '*HexagonalRulesTest*'` — Expected: FAIL, "cannot find symbol HexagonalRules".
- [ ] **Step 3: Implement `static ArchRule domainDependsOnlyOnTheJdk(String root)`**, run, PASS.
- [ ] **Steps 4–11: Repeat red → green, one meta-test at a time,** for rules 2–5. Each fixture holds exactly one violation: `applicationleak` (application → adapter; plus a second meta-test asserting that application → a Spring annotation is **not** a violation), `serviceaccess` (adapter → `application.service`), `adaptercoupling` (`adapter.web` → `adapter.persistence`), `bootstrapleak` (`application` → `bootstrap`). Then one meta-test per rule asserting **no** violation on a fixture `clean/` that follows every rule.
- [ ] **Step 12: Add `forRoot` and `HexagonalArchitectureTest`** (one `@Test` per rule over `com.flightaggregator`), run `./gradlew build`, PASS.
- [ ] **Step 13: Commit and push:** `git commit -m "Check the hexagonal rules with ArchUnit"`

### Task 4: Aggregated JaCoCo coverage gate

**Files:**
- Modify: `buildSrc/src/main/kotlin/flightaggregator.java-conventions.gradle.kts` (apply `jacoco`, `toolVersion` from the catalog: `jacoco = "0.8.16"`)
- Create: `coverage/build.gradle.kts` (plugins `base`, `jacoco-report-aggregation`; `jacocoAggregation(project(...))` for `mock-gds` and every module of Task 2; a `JacocoCoverageVerification` task `coverageVerification` that takes its `executionData`, `classDirectories` and `sourceDirectories` from the `testCodeCoverageReport` task, minus the exclusions; `check` depends on it)
- Modify: `settings.gradle.kts` (include `coverage`)

**Interfaces:**
- Produces: `./gradlew :coverage:testCodeCoverageReport` (HTML + XML under `coverage/build/reports/jacoco/`) and `:coverage:coverageVerification`, run by `./gradlew build`. Minimums read from Gradle properties `coverage.minimum.lines` (default `0.90`) and `coverage.minimum.branches` (default `0.80`). Exclusion list, exact: `**/*Application.class`, `**/config/**`, `**/generated/**`.

- [ ] **Step 1: Prove the gate fails before trusting it passes**
  Run: `./gradlew build -Pcoverage.minimum.lines=1.0`
  Expected: FAIL in `:coverage:coverageVerification`, "lines covered ratio is 0.99, but expected minimum is 1.00" (today `mock-gds` is at 99% lines / 93% branches).
- [ ] **Step 2: Run with the defaults:** `./gradlew build` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Check the report is not vacuous:** `coverage/build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml` contains a `<package name="com/flightaggregator/mockgds/schedule"` element.
- [ ] **Step 4: Check the configuration cache:** run `./gradlew build` twice; the second run prints "Reusing configuration cache."
- [ ] **Step 5: Commit and push:** `git commit -m "Gate the build on 90% line and 80% branch coverage"`

### Task 5: CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `README.md` (status badge under the title)

**Interfaces:**
- Produces: workflow `CI`, job `backend`; later plans add the jobs `frontend`, `contract-drift`, `images` and `e2e` (§6.11) to this same file.

- [ ] **Step 1: Write `ci.yml`:**
  - `on: push` and `pull_request` to `main`;
  - `concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }`;
  - `permissions: contents: read`;
  - job `backend` on `ubuntu-latest`: checkout → setup-java (`distribution: temurin`, `java-version: 25`) → `gradle/actions/setup-gradle` → `./gradlew build` → on `failure()`, upload `**/build/reports/` and `**/build/test-results/` with `actions/upload-artifact`, name `backend-reports`;
  - every action pinned by commit SHA, with its version from Global Constraints in a comment.
- [ ] **Step 2: Push and watch the run:** `gh run watch --exit-status` — Expected: the `backend` job succeeds.
- [ ] **Step 3: Prove a red build is reported:** push a throwaway branch `ci-red-check` (never merged) with one failing assertion, `gh run watch --exit-status` exits non-zero and the run lists the `backend-reports` artefact; delete the branch locally and on the remote (`git push origin --delete ci-red-check`).
- [ ] **Step 4: Add the badge** `[![CI](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml/badge.svg)](https://github.com/dromero001/flight-aggregator/actions/workflows/ci.yml)`, commit and push: `git commit -m "Run the full build in GitHub Actions"`

### Task 6: Dependabot

**Files:**
- Create: `.github/dependabot.yml`

**Interfaces:**
- Produces: ecosystems `gradle` (directory `/`) and `github-actions` (directory `/`); plans 3 and 6 add `docker` and `npm` entries when the first `Dockerfile` and `package.json` exist.

- [ ] **Step 1: Write the config:** `version: 2`; for each ecosystem `schedule: { interval: weekly }`, `cooldown: { default-days: 14 }`, and one `groups` entry matching `"*"` so each ecosystem opens a single PR.
- [ ] **Step 2: Commit and push:** `git commit -m "Keep dependencies current with Dependabot"`
- [ ] **Step 3: Validate:** within a few minutes `gh run list --workflow "Dependabot Updates" --limit 2` lists one successful run per ecosystem. A configuration error shows instead as a failed run, whose log names the invalid key.

### Task 7: Close the run

**Files:**
- Create: `docs/planning/progress/<date>.md` (the red → green evidence of Tasks 1–4, the coverage figures, anything pending)
- Modify: `README.md` (Planning block: this plan, the roadmap and the new progress report)
- Modify: `docs/planning/roadmap.md` (plan 1 → Done)

- [ ] **Step 1:** Write the report and update the indexes.
- [ ] **Step 2:** `./gradlew build` green; the three reviewers on the whole plan's diff (`git diff <plan base>..HEAD`); fix the findings or record the ones the user accepts.
- [ ] **Step 3:** Commit and push: `git commit -m "Record the foundations run"`
