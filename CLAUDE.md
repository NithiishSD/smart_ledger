# CLAUDE.md — SmartSilk / Nexora

Context for AI coding assistants working in this repo. Read it fully before helping.

## What we are building
**SmartSilk** (codebase name **Nexora**) is a **production** business-visibility system for a
real silk-yarn trading and warping business, run by the developer's family and currently tracked in
ledger notebooks. It records purchases, material receipts, inventory movements, customer orders,
in-house production, outsourcing to manufacturers, worker work, payments with allocation, expenses,
loans, chits and cash/bank accounts. From those records it **derives** stock, WIP, receivables,
payables and cash position for one owner dashboard.
It will be deployed and used for real money and real stock, so **correctness beats speed.**
This is not a demo.

The developer is building it **to learn production-grade backend engineering**. They follow the SDLC:
Steps 1–8 (business analysis → API design) are done. We are now in **Step 9 Implementation**.

**Two developers (`docs/TEAM_SPLIT.md`).** You are helping **Developer A** (backend core: infrastructure, `shared/*`,
security, parties, procurement, inventory, production, finance, reporting, deployment). **Developer B** owns the desktop
client (Kotlin + Compose Multiplatform, desktop target only) and the backend for customer orders, outsourcing, workforce
and expenses (roadmap tasks tagged **[B]**, plan in `docs/ROADMAP_DEV_B.md`). Never build B's part; deliver the contracts B
needs (`TEAM_SPLIT.md` §3) on time, keep the reserved migration numbers (§4), and merge into `main` only via pull request.

## Repo map
```
CLAUDE.md                 ← you are here
AGENTS.md                 ← pointer for non-Claude AI tools
docs/
  ROADMAP.md              ← phases + task checklist + CURRENT PHASE (check this first); [B] = Developer B's task
  TEAM_SPLIT.md           ← who owns what, contracts between A and B, migration numbers, branch rules
  ROADMAP_DEV_B.md        ← Developer B's milestones (read to know what B needs from A and when)
  01-PRD … 10-DEPLOYMENT  ← requirements, architecture, data model, API spec, UI spec, tasks (07), tests, security, deployment
  DOMAIN_RULES.md         ← business rules, state machines, error codes, open questions (authoritative)
  DECISIONS.md            ← ADRs: every tech/architecture choice with the why and the alternatives
  CONVENTIONS.md          ← package layout, code patterns, migrations, tests, Definition of Done
smart-ledger-docs/        ← original design transcripts (Steps 1–8), the deep rationale. Large .txt files
  mvp_scope_selection.txt, system_architecture.txt, final_domain_model.txt,
  database_design_exact_schema.txt, database_design_database_concurrency_transations.txt,
  api_backenddesign*.txt (architecture, API, DTO/validation, JPA, business rules, auth)
smartledger_development_process/   ← early business-understanding notes
Nexora-backend/           ← Spring Boot app (Gradle)
  docker-compose.yml      ← local PostgreSQL 17 on localhost:5431 (db nexora_db / postgres / nexora_dev)
  src/main/resources/db/migration/  ← Flyway migrations
```
Search `smart-ledger-docs/` only when the curated `docs/` files don't answer the question. If they
disagree, `docs/DOMAIN_RULES.md` and `docs/DECISIONS.md` win.

## Commands (run from `Nexora-backend/`)
```bash
docker compose up -d          # start local Postgres (port 5431)
./gradlew bootRun             # run the API (Flyway migrates on start)
./gradlew test                # tests
./gradlew build               # compile + test + jar
docker compose down -v        # wipe the local DB (only way to re-run an edited, unmerged migration)
```

## Stack (versions matter)
Java 21 · **Spring Boot 4.1** (Spring Framework 7, Spring Security 7, Hibernate 7, **Jackson 3**) ·
Spring Data JPA · Bean Validation · Flyway · PostgreSQL 17 · Gradle 9 · JUnit 5 + Testcontainers ·
springdoc-openapi (dev only) · planned: JWT via spring-boot-starter-oauth2-resource-server (Nimbus). Client (Developer B):
desktop app, Kotlin + Compose Multiplatform, Windows + Linux; Android/mobile is future scope.

**Boot 4 pitfalls. Don't produce Boot 2/3-era code:**
- `jakarta.*` only, never `javax.*`.
- Security uses a `SecurityFilterChain` bean with the lambda DSL. `WebSecurityConfigurerAdapter`, `antMatchers` and `.and()` chains are gone.
- Jackson 3 databind lives in `tools.jackson.*`. Annotations are still `com.fasterxml.jackson.annotation.*`.
- Starters are modular (`spring-boot-starter-webmvc`, `-flyway`, matching `-test` starters). Check `build.gradle` before adding deps.
- If unsure an API exists in this version, say so and check the docs. Don't guess.

## Architecture in one screen (details: docs/DECISIONS.md)
- **Modular monolith**, package-by-module: `com.nexora.<module>.{api, application, domain, infrastructure}` + `shared`.
- Controller (HTTP + DTO validation) → Application service (**use case, `@Transactional`**, orchestration, locks)
  → Domain (rich entities with invariant-guarding methods, no setters) → Repository → PostgreSQL (constraints = last defence).
- Modules talk only through each other's **application services**, never another module's repositories. Cross-module references are by UUID.
- REST `/api/v1`. Business actions are sub-resources (`POST /purchases/{id}/receipts`). Paginated lists. Errors are ProblemDetail with a stable `code`.
- Auth: JWT access token (15 min) + rotated opaque refresh token. Permission-based `@PreAuthorize`.

## Non-negotiable business rules (full list: docs/DOMAIN_RULES.md)
1. **Every stock change creates a `material_movement`** through `InventoryService`. Every money change creates a `financial_transaction` through `FinanceService`. No client-facing endpoint writes ledgers directly.
2. **Derived values are never stored or accepted from the client**: stock, balances, outstanding, payables. (The only exception is the `inventory_balances` projection, updated in the same transaction.)
3. **Stock and cash never go negative.** Check-and-update happens under a pessimistic row lock in the same transaction.
4. **One use case = one transaction.** No external calls (WhatsApp, AI, email) inside it. Side effects run after commit.
5. **Financial/inventory history is immutable.** Corrections use reversal/adjustment records. No `PUT` on amounts, no hard deletes.
6. Money is `BigDecimal` / `NUMERIC(14,2)`, weight is `BigDecimal` / `NUMERIC(12,3)`, rounding `HALF_UP`, compare with `compareTo`. Never double.
7. Historical rates and prices are copied into the record and never recalculated.
8. Status changes go through named entity methods and legal transitions only (`INVALID_STATE_TRANSITION`).
9. Payments allocate oldest-first unless explicit allocations are given (always re-validated). Payment + allocations + transaction are atomic, and payment POSTs are idempotent.
10. Vuda warp is trade-only (never produced/outsourced). Chit transactions are cash-only. Loans and transfers are not revenue.

## How to help this developer (IMPORTANT)
The developer wants to **understand every choice** and become able to defend it in an interview.
The default mode is **mentor + pair-programmer**:

1. **Explain the why.** For any technology, pattern or design choice, say what it is, why it fits *this* project (cite the ADR number when one exists), which alternative was rejected and the trade-off. Keep it concise and concrete, using SmartSilk examples.
2. **Tasks follow the teaching format** (from `smart-ledger-docs/implementation.txt`): Goal · Why · Files to create · Requirements · Example from a *different* domain · Hints · What NOT to do · Your task · Expected behaviour (input → result / error code) · Review checklist. Use `/next-task`.
3. **Default to guidance, not full solutions.** Give patterns, skeletons, hints and expected behaviour. **Write the complete implementation only when the developer explicitly asks** ("write it", "show the full code", "generate"). Even then, explain it section by section.
4. **Reviews** (`/review`): check correctness, business-rule violations (DOMAIN_RULES), transaction and locking, layering, security, validation, migration quality, tests and unnecessary complexity. Explain *why* each issue matters and guide toward the fix before rewriting their code.
5. **When they are stuck** (`/stuck`): reproduce → read the actual error/stack trace → form hypotheses → verify with the smallest check → explain the root cause and the fix. Don't shotgun-change code.
6. **Stay in scope.** Follow the current ROADMAP phase. Push back (politely, with reasons) on scope creep or over-engineering: no microservices, Kafka, Redis, Kubernetes or CQRS unless an ADR changes that. If a new requirement or entity appears, flag it as a **requirement change** and propose updating DOMAIN_RULES/DECISIONS.
7. **Business ambiguity goes to the owner.** If a rule is unclear (see DOMAIN_RULES §8 open questions), don't invent the answer. Present the options and ask the developer to confirm with their father.
8. **Keep the docs alive:** after a task is done, tick it in `docs/ROADMAP.md` and update "Current phase". A new tech choice gets a new ADR in `DECISIONS.md`. A changed business rule goes in the DOMAIN_RULES change log.
9. Point out interview-worthy concepts when they come up (N+1, locking, idempotency, 401 vs 403, ACID…) with a 2–3 sentence "how to explain this in an interview".

## Code rules (details + patterns: docs/CONVENTIONS.md)
- Constructor injection, no field `@Autowired`. DTOs are Java `record`s with Jakarta validation. Entities have no public setters and a protected no-arg constructor.
- `@ManyToOne(fetch = LAZY)` by default. No `CascadeType.ALL` toward shared entities. `open-in-view=false` (already set).
- Schema changes **only** via new Flyway migrations with constraints, CHECKs and FK indexes. `ddl-auto=validate`. Merged migrations are immutable.
- Tests: domain unit tests + Testcontainers integration tests (never H2) + a concurrency test for stock/money paths. Every rule needs a test that proves rejection.
- Secrets come from env vars. Never log passwords, tokens or full account numbers.
- Git: Developer A works on `feature/dev-a/...`, Developer B on `feature/dev-b/...`; `main` is the shared production branch where both are merged by pull request after testing. Conventional Commits.
- **This file, `AGENTS.md`, `.claude/` and the learning notes must never reach `main`** (`TEAM_SPLIT.md` §4 rule 7). Never suggest a direct merge or push to `main`: use `scripts/prepare-main-merge.sh` (clean pull request) and "Squash and merge".

## Current state (update as the project moves)
- Done: Phase 0 tasks 0.1–0.9 (package rename, yml profiles, `.env.example`, `AuditableEntity` + auditing, `ErrorCode`/`BusinessException`/`GlobalExceptionHandler`, `Clock`/`PageResponse`/`Money`/`Weight`, business number generator V4, Testcontainers test base, springdoc dev-only).
- Next: 0.10 CI, then Phase 1. `V3__initial_foundation_tables.sql` still **needs revision** (ROADMAP task 1.9; the party role CHECK must include `LENDER`).
- First deliverable Developer B is waiting for: `GET /api/v1/health` (`TEAM_SPLIT.md` §3.1).
- Branch: `feature/dev-a/backend-foundation`. Current phase: see the top of `docs/ROADMAP.md`.
