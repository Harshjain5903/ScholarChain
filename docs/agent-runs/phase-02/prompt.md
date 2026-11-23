# Phase 02 Work Order

Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch. Required projects: core, hadoop, entityResolution, sparkAnalytics, storage, rag, services. Scala 3 everywhere, pin every dependency version in a single ledger, use uPickle for JSON, use ScalaTest for tests, keep Hadoop and Spark dependencies Provided, use scalafmt with Scala 3 dialect, create markTestRun task, define aliases fmt/check/packageJobs/dockerLocal, create build.sbt/project files/.scalafmt.conf/.gitignore/empty module source roots/empty top-level directories. Verify with sbt Test/compile and sbt check.


## Exact human work orders (RECONSTRUCTED 2026-11-23)
Copied word for word from the saved Claude Code session transcripts (`~/.claude/projects/-Users-harshjain-Documents-scholarchain/<session>.jsonl`). Text above this section is the original file and is unchanged.

### Work order to build-engineer (2026-11-18T00:31Z)

Session `210c52dc-8262-459f-b168-0414dd0c4542`, message `9e2b6ba0-7059-49a2-b22d-01383ffd0382`, sent 2026-11-18T00:31:40.620Z.

````text
Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch.

Required projects:
- core
- hadoop
- entityResolution
- sparkAnalytics
- storage
- rag
- services

Required principles:
- Scala 3 everywhere.
- Use Hadoop and Spark Java or DataFrame APIs where that avoids unsupported Scala binary assumptions.
- Pin every dependency and plugin version in a single version ledger.
- No floating or latest versions.
- Select one JSON library for the entire Scala codebase. Prefer uPickle unless a documented compatibility problem requires another choice.
- Select ScalaTest or MUnit and use it consistently.
- Keep Hadoop and Spark dependencies Provided when the runtime supplies them.
- Keep test-only libraries in Test scope.
- Keep logging implementation in Runtime scope.
- Use scalafmt with the Scala 3 dialect.
- Create a markTestRun task that updates .claude/.last-test-run only after tests pass.
- Define aliases: fmt, check, packageJobs, and dockerLocal.
- check must run format checking, compilation, tests, contract tests that do not need AWS, and markTestRun.
- Container images must use a pinned Java runtime, a non-root user, and percentage-based memory limits.
- Build behavior may depend only on explicitly documented environment variables.
- No secrets, cloud endpoints, or deployment procedures in the build definition.

Create:
- build.sbt
- project/build.properties
- project/plugins.sbt
- .scalafmt.conf
- .gitignore
- local compose file for emulators or test dependencies only when justified
- empty module source roots
- empty required top-level directories from the approved ownership map

Verify on a clean repository state with sbt Test/compile and then sbt check once the marker test exists.
Report the complete version ledger, module dependency graph, environment input surface, packaging choices, and any compatibility risk involving Scala 3, Hadoop, or Spark.
````

### Review order to code-reviewer

Session `210c52dc-8262-459f-b168-0414dd0c4542`, message `a4e2ed01-4878-4673-b77a-c7643c37f28a`, sent 2026-11-18T00:56:49.390Z.

````text
Use the code-reviewer agent to perform a read-only review of the Phase 2 build changes. Review build.sbt, project/Dependencies.scala, project/plugins.sbt, project/build.properties, .scalafmt.conf, and .gitignore. Check for: correct module dependency boundaries, no upward dependency violations, version pinning completeness, Provided/Test/Runtime scope correctness, no secrets or endpoints, and alias definitions. Return APPROVE or REQUEST_CHANGES.
````

### Evidence instruction to the orchestrator

Session `210c52dc-8262-459f-b168-0414dd0c4542`, message `337709dc-8dc5-4756-9f35-f4c11945d44b`, sent 2026-11-18T01:08:19.451Z.

````text
Create the Phase 2 evidence directory and files, then commit them.

1. Create docs/agent-runs/phase-02/ directory

2. Create docs/agent-runs/phase-02/prompt.md with the exact Phase 2 work order:
"Use the build-engineer agent to create the ScholarChain sbt multi-project build and repository skeleton from scratch. Required projects: core, hadoop, entityResolution, sparkAnalytics, storage, rag, services. Scala 3 everywhere, pin every dependency version in a single ledger, use uPickle for JSON, use ScalaTest for tests, keep Hadoop and Spark dependencies Provided, use scalafmt with Scala 3 dialect, create markTestRun task, define aliases fmt/check/packageJobs/dockerLocal, create build.sbt/project files/.scalafmt.conf/.gitignore/empty module source roots/empty top-level directories. Verify with sbt Test/compile and sbt check."

3. Create docs/agent-runs/phase-02/agent-report.md with:
STATUS: COMPLETE
OWNER: build-engineer
FILES CREATED: build.sbt, project/Dependencies.scala, project/build.properties, project/plugins.sbt, .scalafmt.conf, .gitignore (modified), all 7 module source roots under modules/, all top-level skeleton directories, modules/core/src/test/scala/edu/scholarchain/core/BuildSmokeSpec.scala
COMMANDS RUN: sbt Test/compile (success), sbt check (success)
TEST RESULTS: BuildSmokeSpec passed (1 test)
VALIDATION RESULTS: All modules compile, no cycles, versions pinned in Dependencies.scala
Version ledger: Scala 3.3.4, sbt 1.10.6, uPickle 4.0.2, Typesafe Config 1.4.3, Hadoop 3.3.6 (Provided), Spark 3.5.3 (Provided), AWS SDK v2 2.29.26, Cask 0.9.4, ScalaTest 3.2.19, Logback 1.5.12, SLF4J 2.0.16, scalafmt 3.8.3
RISKS: Cask 0.9.4 depends on uPickle 3.x internally; forced to 4.0.2 — may need attention in Phase 10
NEXT GATE: Phase 3 — domain-engineer + dataset-engineer

4. Create docs/agent-runs/phase-02/human-gate.md with:
Phase: 02
Agent: build-engineer
Date: 2026-11-17
Decision: ACCEPTED
Reviewed: build.sbt, project files, module structure, .scalafmt.conf, .gitignore, smoke test, version ledger, module dependency graph
Code review: code-reviewer returned APPROVE (minor/note findings only, non-blocking)
Manual changes: None
Remaining risk: Cask/uPickle version mismatch — monitor in Phase 10
Commit: 102595d

5. Git add and commit with message "phase-02: add agent run evidence"

Do NOT push. Do NOT modify any other files.
````
