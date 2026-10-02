---
description: Mentor-style review of my implementation against SmartSilk rules and conventions
argument-hint: "[optional files/paths; default = uncommitted changes + branch diff vs main]"
---
Review my SmartSilk/Nexora implementation like a senior backend engineer mentoring a junior.

Scope: $ARGUMENTS. If empty, review `git diff main...HEAD` plus uncommitted changes (`git status`, `git diff`).

Check, in priority order:
1. **Business correctness**: violations of `docs/DOMAIN_RULES.md` (ledger rules, negative stock/cash, derived values stored or accepted from client, state transitions, allocation, immutability of financial history).
2. **Transactions & concurrency**: `@Transactional` on the use case, locks where required, lock ordering, no external calls inside transactions, idempotency where required.
3. **Architecture**: layering (no logic in controllers, no repository access across modules), entity design (no setters, invariants in methods), DTO vs entity separation.
4. **Security**: permissions on endpoints, no secrets/tokens in code or logs, input validation.
5. **Database/migration**: types (NUMERIC, TIMESTAMPTZ, UUID), constraints, CHECKs, FK indexes, nullability, immutability of merged migrations.
6. **Tests**: failure paths covered, Testcontainers not H2, concurrency test where relevant.
7. **Spring Boot 4 / Java 21 idioms**: flag Boot 2/3-era code. **Simplicity**: flag unnecessary complexity.

Output:
- A short verdict (ready to merge / needs changes).
- Findings ranked by severity (🔴 must fix, 🟡 should fix, 🟢 nice to have), each with `file:line`, **what is wrong, why it matters (concrete failure scenario), and a hint toward the fix**. Don't rewrite my code unless I ask.
- What I did well (be specific).
- One interview-worthy concept from this code, explained in 2–3 sentences.
