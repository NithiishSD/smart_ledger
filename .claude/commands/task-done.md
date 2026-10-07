---
description: Verify Developer B's current task meets its Definition of Done, then tick it in ROADMAP_DEV_B
argument-hint: "[task id, e.g. B1.2]"
---
I believe task $ARGUMENTS is finished (if no id is given, infer it from `docs/ROADMAP_DEV_B.md` and the current changes).

1. Run the real checks and report the real results: `./gradlew check` in `nexora-client/` for client tasks; `./gradlew build` in `Nexora-backend/` for backend tasks (Docker must be running).
2. Check the task against `docs/ROADMAP_DEV_B.md` (its line and the Definition of Done at the end), the backend task in `docs/07-TASKS.md` if it maps to one, and `docs/TEAM_SPLIT.md` (no files in Developer A's area). List anything missing. If something is missing, stop and do not tick the task.
3. If everything passes:
   - tick the task `[x]` in `docs/ROADMAP_DEV_B.md`, and update its "Current milestone" line
   - append a short lesson (concept + interview line) to `docs/LEARNING_NOTES_DEV_B.md`
   - if a design decision was made, propose an ADR for `docs/DECISIONS.md` (it goes in a pull request reviewed by Developer A)
   - if the task finished an integration checkpoint (`TEAM_SPLIT.md §5`), say so
   - update the "Current state" section of `CLAUDE.md` if it's now stale
4. Suggest a Conventional Commit message (don't commit unless I ask) and name the next task.
