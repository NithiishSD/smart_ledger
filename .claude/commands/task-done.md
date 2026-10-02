---
description: Verify the current task meets its Definition of Done, then tick it in the roadmap
argument-hint: "[task id, e.g. 2.5]"
---
I believe roadmap task $ARGUMENTS is finished (if no id is given, infer it from `docs/ROADMAP.md` and the current changes).

1. Run `./gradlew build` in `Nexora-backend/` and report the real result.
2. Check the task against its requirements/expected behaviour and the Definition of Done in `docs/CONVENTIONS.md §11`. List anything missing. If something is missing, stop and do not tick the task.
3. If everything passes:
   - tick the task `[x]` in `docs/ROADMAP.md`, and update "Current phase" / "Last updated" if the phase is complete
   - if a design decision was made during the task, add or update an ADR in `docs/DECISIONS.md`
   - if a business rule changed, update `docs/DOMAIN_RULES.md` and its change log
   - update the "Current state" section of `CLAUDE.md` if it's now stale
4. Suggest a Conventional Commit message (don't commit unless I ask) and name the next task.
