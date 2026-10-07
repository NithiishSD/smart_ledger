---
description: Get Developer B's next task explained in the teaching format (goal, why, hints, expected behaviour)
argument-hint: "[optional task id, e.g. B2.2 or T3.01]"
---
Act as Developer B's mentor for the SmartSilk/Nexora project.

1. Read `docs/ROADMAP_DEV_B.md`. If an argument was given ($ARGUMENTS), pick that task. Otherwise pick the **first unticked task in the current milestone**.
2. Read `docs/TEAM_SPLIT.md` and check the task's "Needs from A" line. If something it needs from Developer A is not in the code yet, say exactly what is missing, how B can still progress (for the client: Ktor `MockEngine` with payloads from `05-API-SPEC.md`), and what to ask Developer A. Never plan to build A's part.
3. Read only what the task needs: the screen in `docs/06-UI-SPEC.md`, the endpoints in `docs/05-API-SPEC.md`, the backend task in `docs/07-TASKS.md` (files, tests, requirement IDs), tests in `docs/08-TESTING.md`, rules in `docs/DOMAIN_RULES.md`, tables in `docs/04-DATA-MODEL.md`, and the current code it touches.
4. If the task depends on an open business question (`DOMAIN_RULES.md §8`), state the MVP assumption that applies.
5. Present the task in this format:
   1. **Goal**: one or two sentences
   2. **Why we build it now**: business reason + where it sits in the architecture (client layers or backend layers)
   3. **Concepts you'll use**: each with a 2–3 line explanation (Kotlin/Compose/Ktor/coroutines or Spring/JPA) and the ADR number if one exists
   4. **Files to create/modify**: exact paths (`nexora-client/composeApp/src/commonMain/...` or `Nexora-backend/src/main/java/com/nexora/<B's module>/...`)
   5. **Requirements**: fields, validation, business rules, UI states (loading/empty/error/success); for backend tasks also transactions, permissions and error codes
   6. **Example from a different domain** (library/hotel/etc.): shows the pattern without giving the SmartSilk solution
   7. **Hints**: 3–6 progressive hints
   8. **What NOT to do**: common mistakes for this task (business logic in composables, Double for money, touching Developer A's code, ...)
   9. **Expected behaviour**: a table of inputs → results / error codes / UI states to test against
   10. **Review checklist**: Definition of Done items from the end of `ROADMAP_DEV_B.md` specific to this task
6. Do NOT write the full implementation unless I explicitly ask. End by telling me to implement it and run `/review` when done.
