---
description: Mentor-style review of Developer B's implementation against SmartSilk rules, contracts and conventions
argument-hint: "[optional files/paths; default = uncommitted changes + branch diff vs main]"
---
Review my SmartSilk/Nexora implementation like a senior engineer mentoring a junior.

Scope: $ARGUMENTS. If empty, review the diff of my branch against `main` (`main...HEAD`) plus uncommitted changes.

Check, in priority order:
1. **Ownership**: does the change touch Developer A's area (`docs/TEAM_SPLIT.md §2, §4`)? Flag every such file.
2. **Business correctness**: violations of `docs/DOMAIN_RULES.md` (stock/money only via A's services, derived values never stored/accepted/computed by the client, state transitions via named methods, historical rates copied, vuda rules).
3. **Contract match**: request/response fields, status codes and error codes exactly as in `docs/05-API-SPEC.md`; cross-module calls only through `TEAM_SPLIT.md §3` contracts.
4. **Client architecture**: shared code in `commonMain`; platform code only behind `expect`/`actual`; no API calls or business logic in composables; StateFlow UI state with loading/empty/error/success; exact decimals (never `Double`); Idempotency-Key on retry-sensitive submits; strings in `strings.properties`.
5. **Backend (B's slices)**: `@Transactional` on the use case, layering, entity design (no setters), DTO validation, `@PreAuthorize`, migration quality (constraints, FK indexes, reserved V-number).
6. **Tests**: the tests named in `07-TASKS.md`/`08-TESTING.md`, rejection paths, MockEngine payloads copied from `05`, Testcontainers not H2.
7. **Idioms and simplicity**: flag outdated Spring Boot 2/3 patterns; flag unnecessary complexity.

Output:
- A short verdict (ready for a pull request / needs changes).
- Findings ranked by severity (🔴 must fix, 🟡 should fix, 🟢 nice to have), each with `file:line`, **what is wrong, why it matters (concrete failure scenario), and a hint toward the fix**. Don't rewrite my code unless I ask.
- What I did well (be specific).
- One interview-worthy concept from this code, explained in 2–3 sentences.
