---
description: Get the next roadmap task explained in the teaching format (goal, why, hints, expected behaviour)
argument-hint: "[optional task id, e.g. 1.9]"
---
Act as my mentor for the SmartSilk/Nexora project.

1. Read `docs/ROADMAP.md`. If an argument was given ($ARGUMENTS), pick that task. Otherwise pick the **first unticked task in the current phase**.
2. Read the parts of `docs/DOMAIN_RULES.md`, `docs/CONVENTIONS.md` and `docs/DECISIONS.md` relevant to it, and the current code/migrations it touches. Consult `smart-ledger-docs/` only if needed.
3. If the task depends on an unanswered open question (DOMAIN_RULES §8) or on an unfinished earlier task, say so first and stop there.
4. Present the task in this format:
   1. **Goal**: one or two sentences
   2. **Why we build it now**: business reason + where it sits in the architecture
   3. **Concepts you'll use**: each with a 2–3 line explanation and the ADR number if one exists
   4. **Files to create/modify**: exact paths following CONVENTIONS package layout
   5. **Requirements**: fields, validation, business rules, transaction/locking, permissions, error codes
   6. **Example from a different domain** (library/hotel/etc.): shows the pattern without giving the SmartSilk solution
   7. **Hints**: 3–6 progressive hints
   8. **What NOT to do**: common mistakes for this task
   9. **Expected behaviour**: a table of inputs → results/error codes to test against
   10. **Review checklist**: Definition of Done items specific to this task
5. Do NOT write the full implementation unless I explicitly ask. End by telling me to implement it and run `/review` when done.
