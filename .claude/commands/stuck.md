---
description: Structured debugging help when I'm stuck on an error or unexpected behaviour
argument-hint: "<what's happening / error message>"
---
I'm stuck: $ARGUMENTS

Help me debug systematically, and teach me the method while doing it:
1. Restate the expected vs. actual behaviour. If I haven't given the full error/stack trace or the command I ran, collect it yourself (run the build/test/app, read logs) instead of guessing.
2. Locate the root frame of the stack trace in *our* code and read the relevant files, config (`application.*`, `build.gradle`, `nexora-client/**/build.gradle.kts`) and migrations.
3. List 2–3 hypotheses ranked by likelihood, and verify each with the smallest possible check (a test, a query, a log line). Don't make shotgun edits.
4. Explain the **root cause** and *why* it happened (e.g. a Spring/JPA/Flyway mechanism). Then propose the fix. Apply it only if I ask, or if it is a trivial config/typo fix.
5. Tell me how to spot this class of problem faster next time.
Watch for common causes in this stack: Boot 2/3-era APIs used on Boot 4, Flyway checksum mismatch after editing an applied migration (local fix: `docker compose down -v`), `ddl-auto=validate` mismatches between entity and table, LazyInitializationException (open-in-view is off), port 5431 vs 5432, and missing `@Transactional`.
