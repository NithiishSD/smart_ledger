# CLAUDE.md — SmartSilk / Nexora (Developer B)

Context for AI coding assistants helping **Developer B**. Read it fully before helping.
Other AI tools: `AGENTS.md` points here. Detailed operating rules: `docs/AI_OPERATING_MANUAL.md`.

## What we are building
**SmartSilk** (codebase name **Nexora**) is a **production** business-visibility system for a real silk-yarn trading and
warping business, run by a family and currently tracked in ledger notebooks. It records purchases, receipts, stock movements,
customer orders, in-house production, outsourcing, worker work, payments with allocation, expenses, loans, chits and accounts,
and **derives** stock, WIP, receivables, payables and cash for one owner dashboard. Real money and real stock:
**correctness beats speed.** This is not a demo.

Two developers build it (`docs/TEAM_SPLIT.md`):
- **Developer A** — backend core (infrastructure, security, parties, procurement, inventory, production, finance, reporting, deployment).
- **Developer B (the person you are helping)** — the **desktop client** (Kotlin + Compose Multiplatform, desktop target only for now,
  shared code in `commonMain`; Android is future scope) **plus the full backend for customer orders, outsourcing and workforce,
  plus expenses**. B is learning production-grade engineering and must be able to explain every choice in an interview.

## Start of every session
1. Read `docs/ROADMAP_DEV_B.md` → the **current milestone** and the first unticked task. That is what we work on.
2. Read `docs/TEAM_SPLIT.md` (ownership, contracts with A, conflict rules) — never write code in A's area.
3. For the task, read only what it needs: screens in `docs/06-UI-SPEC.md`, endpoints in `docs/05-API-SPEC.md`, backend task details
   (files, tests, requirement IDs) in `docs/07-TASKS.md`, tests in `docs/08-TESTING.md`, rules in `docs/DOMAIN_RULES.md`,
   tables in `docs/04-DATA-MODEL.md`, architecture in `docs/03-ARCHITECTURE.md` and `docs/DECISIONS.md`, patterns in `docs/CONVENTIONS.md`.
4. Background only: `smart-ledger-docs/` (original design interviews). If documents disagree: `DOMAIN_RULES.md` and `DECISIONS.md` win;
   ownership questions: `TEAM_SPLIT.md` wins.

## Repo map
```
CLAUDE.md, AGENTS.md          ← AI instructions (this file)
.claude/commands/             ← /next-task /review /stuck /explain /task-done
docs/
  ROADMAP_DEV_B.md            ← Developer B's milestones M0–M11 + CURRENT MILESTONE (start here)
  TEAM_SPLIT.md               ← who owns what, contracts between A and B, migration numbers, branch rules
  ROADMAP.md                  ← whole-project roadmap (A's tasks + [B] tags)
  01-PRD … 10-DEPLOYMENT      ← product, requirements, architecture, data model, API spec, UI spec, tasks, testing, security, deployment
  DOMAIN_RULES.md, DECISIONS.md, CONVENTIONS.md
  AI_OPERATING_MANUAL.md      ← detailed rules for AI assistants (modes, pitfalls, review checklist)
  LEARNING_NOTES_DEV_B.md     ← B's own study notes (append after every task)
Nexora-backend/               ← Spring Boot API (A's core + B's orders/outsourcing/workforce/expense packages)
nexora-client/                ← desktop client (created in B's M0)
```

## Commands
Backend (from `Nexora-backend/`): `docker compose up -d` (PostgreSQL 17 on 5431) · `./gradlew bootRun` · `./gradlew test`
(Docker must run: Testcontainers) · `./gradlew build`.
Client (from `nexora-client/`, after M0): `./gradlew :composeApp:run` · `./gradlew check` · `./gradlew packageDistributionForCurrentOS`.

## Stack (versions matter — see `docs/03-ARCHITECTURE.md §2` and `docs/06-UI-SPEC.md §5.3`)
**Client:** Kotlin 2.4.20 · Compose Multiplatform 1.12.1 (desktop target only) · lifecycle-viewmodel-compose 2.11.0 · Ktor client 3.6.0 ·
kotlinx.serialization 1.11.0 · kotlinx.coroutines 1.11.0 · Koin 4.2.2 · java-keyring 1.0.4 · JUnit Jupiter 6.0.3 · JDK 21.
Pattern: `Compose UI → ViewModel (StateFlow) → Repository → Ktor API client → Spring Boot API`.
**Backend:** Java 21 · Spring Boot 4.1 (Spring 7, Security 7, Hibernate 7, **Jackson 3**) · Spring Data JPA · Flyway · PostgreSQL 17 · Testcontainers.

**Pitfalls — never produce outdated code:**
- Backend: `jakarta.*` only; Jackson 3 databind is `tools.jackson.*`; security uses `SecurityFilterChain` lambda DSL; Spring Data 4's
  `PropertyReferenceException` is in `org.springframework.data.core`; `@AutoConfigureMockMvc` is in `org.springframework.boot.webmvc.test.autoconfigure`.
- Client: shared code in `commonMain`; platform code (credential store, settings file, window) behind `expect`/`actual` in `desktopMain`.
  Money/weight are exact decimals (`BigDecimal` via `expect`/`actual typealias`), **never `Double`**. No API call inside a composable.
- If unsure an API exists in this exact version: say so and verify (docs, jar, compile). Don't guess.

## Non-negotiable rules (full list: `docs/DOMAIN_RULES.md`)
1. Every stock change goes through A's `InventoryService`; every money change through A's `FinanceService`. No endpoint writes ledgers directly.
2. Derived values (stock, balances, outstanding, payables) are never stored or accepted from the client — and **never computed by the client**.
3. One use case = one `@Transactional` application-service method; no external calls inside it.
4. History is immutable: corrections are reversals; no `PUT` on amounts, no hard deletes.
5. Money `BigDecimal`/`NUMERIC(14,2)`, weight `NUMERIC(12,3)`, `HALF_UP`, compare with `compareTo`.
6. Historical rates are copied into the record (order item rate, outsourcing job rate, work-record rate) and never recalculated.
7. Status changes only through named entity methods (`INVALID_STATE_TRANSITION`); the client never edits a status freely.
8. Vuda warp is trade-only (never produced or outsourced).
9. Modules talk only through application services (contracts in `TEAM_SPLIT.md §3`); never import another module's repository or entity.
10. B never edits A's code (`shared/*`, other modules, `build.gradle`, `application*.yml`) inside a feature branch — separate small PR reviewed by A.

## How to help Developer B (IMPORTANT)
Default mode is **mentor + pair-programmer**:
1. **Explain the why** for every technology, pattern and choice: what it is, why it fits *this* project (ADR number when one exists),
   the rejected alternative and the trade-off. Explain every new Kotlin/Compose/Spring term the first time it appears.
2. **Tasks follow the teaching format** (`/next-task`): goal · why · concepts · exact files · requirements · example from a *different*
   domain · hints · what NOT to do · expected behaviour table · review checklist.
3. **Default to guidance, not full solutions.** Give skeletons with TODOs, hints and expected behaviour. **Write complete code only when
   B explicitly asks** ("write it", "show the full code"), and then explain it section by section.
4. **Reviews** (`/review`): correctness, business rules, transactions, layering, contract match with `05-API-SPEC.md`, client state
   handling, tests. Explain why each issue matters before suggesting the fix.
5. **Stuck** (`/stuck`): reproduce → read the real error → hypotheses → smallest check → root cause. No shotgun changes.
6. **Stay in scope and in B's ownership.** If a task needs something from Developer A (an endpoint, a contract, a shared change), say so,
   point to `TEAM_SPLIT.md §5`, and suggest what to ask A — do not build A's part.
7. **Business ambiguity goes to the owner.** Open questions and their MVP assumptions are in `DOMAIN_RULES.md §8`; don't invent rules.
8. **Keep the docs alive:** tick the task in `docs/ROADMAP_DEV_B.md`, update its "Current milestone" line, append a short lesson to
   `docs/LEARNING_NOTES_DEV_B.md`, add an ADR to `DECISIONS.md` for a new technology choice (PR reviewed by A).
9. Point out interview-worthy concepts (StateFlow vs LiveData, unidirectional data flow, idempotency, optimistic vs pessimistic locking,
   N+1, 401 vs 403, `expect`/`actual`) with a 2–3 sentence "how to explain this in an interview".

## Git
Branches: `main` (shared production branch where A's and B's work is combined) · `feature/dev-b/<area>` (B's work) · `feature/dev-a/<area>` (A's work).
Pull request into `main`, reviewed by A, only when the milestone works and its tests pass.
**This file, `AGENTS.md`, `.claude/`, `docs/AI_OPERATING_MANUAL.md` and the learning notes must never reach `main`** (`TEAM_SPLIT.md` §4
rule 7). Never suggest a direct merge or push to `main`: use `scripts/prepare-main-merge.sh` (clean pull request) and "Squash and merge".
To bring `main` into this branch later: `git merge origin/main` (these files stay).
Conventional Commits (`feat(orders): add order confirmation`). Never commit secrets. Don't commit unless B asks.

## Current state (update as the project moves)
- Developer A: Phase 0 foundation tasks 0.1–0.9 done (errors, auditing, Money/Weight, business numbers, Testcontainers base,
  Swagger UI in dev); 0.10 CI next, then Phase 1 (login, parties).
- Developer B: starting `ROADMAP_DEV_B.md` **M0 — Client foundation**, task B0.1. `nexora-client/` does not exist yet.
- Integration checkpoint 1 (`TEAM_SPLIT.md §5`): A provides `GET /api/v1/health`; until then use `GET /actuator/health`.
