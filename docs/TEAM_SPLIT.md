# Team Split — Developer A and Developer B

SmartSilk (Nexora) is built by two developers. This file says **who owns what**, **how the two halves talk to each
other**, and **the rules that stop the two branches from conflicting**. When this file and another document disagree
about ownership, this file wins. Business rules still come from `DOMAIN_RULES.md`, and API shapes from `05-API-SPEC.md`.

## 1. Roles in one paragraph each

**Developer A — backend core.** Owns the Spring Boot infrastructure, database integrity, security, and the backend
modules for parties, procurement, inventory, production, finance (accounts, transactions, payments, allocations,
transfers, loans, chits), reporting/dashboard, notifications, documents, hardening and deployment. Owns `shared/*`.

**Developer B — client + three backend vertical slices.** Owns the **desktop client** (Kotlin + Compose
Multiplatform, desktop target only for now, Android later) and **all of its screens**, plus the
**full backend** for **customer orders**, **outsourcing** and **workforce (work records)**, plus **expenses and personal
drawings** (finance cross-learning feature). Writes API contract tests for A's endpoints that the client uses.

## 2. Ownership by module and task

Task IDs refer to `07-TASKS.md`. "Owner" writes the code and the tests; the other developer reviews the pull request.

| Area | Owner | `07-TASKS` tasks | Notes |
|---|---|---|---|
| Foundation, `shared/*` (errors, security, numbering, Money/Weight, Clock, idempotency, audit) | A | M0, T1.01, T3.06 | B asks A for any change in `shared/*` (new error code, new permission) |
| Identity: login, JWT, refresh, lockout, owner bootstrap | A | T1.01–T1.07 | B builds the login screens |
| Parties backend | A | T1.08–T1.13 | `PartyService.requireActiveWithRole` is what B's modules call |
| Procurement backend (purchases, receipts, returns) | A | T2.07–T2.10 | |
| Inventory backend (ledger, balances, `InventoryService`) | A | T2.01–T2.06 | B's orders and outsourcing call `InventoryService` |
| Module-boundary test | A | T2.11 | applies to everyone's code |
| **Customer orders backend** (schema V8, aggregate, endpoints, delivery) | **B** | **T3.01–T3.04** | plus the `OrderService` / `OrderQueryService` contracts in §3 |
| Finance core: accounts, `FinanceService` (V9), allocations/idempotency/audit (V10) | A | T3.05, T3.06 | |
| Payments, allocation, outstanding, payables, reversal, opening obligations | A | T3.07–T3.13 | reads B's obligations through §3 contracts |
| MVP end-to-end scenario test | A + B | T3.14 | A writes it; B's order endpoints must be on `main` |
| Production backend (V11) | A | T4.01–T4.04 | calls B's `OrderService.lockForProduction` |
| **Outsourcing backend** (V12) | **B** | **T4.05–T4.07** | plus `OutsourcingQueryService` obligations (§3) |
| Manufacturer payable + payments | A | T4.08 | uses B's `OutsourcingQueryService` |
| **Workforce backend** (V13, work records, reversal) | **B** | **T4.09**, work-record parts of **T4.10** | plus `WorkRecordQueryService` obligations (§3) |
| Worker payable + worker payments | A | payment parts of T4.10 | uses B's `WorkRecordQueryService` |
| Production/outsourcing scenario tests | A + B | T4.11 | each writes the half for their module |
| Transfers, adjustments, cash position, loans, chits | A | T5.01, T5.03–T5.06 | |
| **Expenses and personal drawings** (finance cross-learning) | **B** | **T5.02** | money moves only through A's `FinanceService.recordTransaction` |
| Dashboard, reports, notifications, documents (backend) | A | M6 | B builds the screens |
| Hardening, deployment, go-live | A (B reviews) | M7, M8 | B owns client packaging (MSI/DEB) and client CI |
| **Client (Kotlin Multiplatform)** — every screen in `06-UI-SPEC.md` | **B** | replaces `07` M9 | step-by-step plan: `ROADMAP_DEV_B.md` |
| API contract tests for A's endpoints | B | `ROADMAP_DEV_B.md` | tests only, in `src/test/java/com/nexora/contract/` |

**Why payables and outstanding belong to A:** an outstanding amount is "obligations minus allocations". Allocations live in
finance (A). If B's modules also read allocations, finance and orders would depend on each other in a cycle. So B's
modules expose their **obligations** (what is owed and since when), and A's finance module computes outstanding/payable.
B still builds the worker payable and customer outstanding **screens**.

## 3. Contracts between the two halves

Every cross-module call goes through an application service. Never import another module's repository or entity
(the module-boundary test, T2.11, fails the build). Shapes below are the agreed contracts; details in `03-ARCHITECTURE.md §5`.

### 3.1 Provided by A, used by B
| Contract | Used by B for | Available after |
|---|---|---|
| `PartyService.requireActiveWithRole(UUID partyId, PartyRole role)` → `PartyRef` | orders (CUSTOMER), outsourcing (MANUFACTURER), work records (WORKER) | T1.12 |
| `InventoryService.recordMovement(MovementCommand)` (MANDATORY transaction, locks balances in the global order) | order delivery (`DELIVERY`, `DIRECT_SALE`), outsourcing issue/receive (`OUTSOURCE_ISSUE`, `OUTSOURCE_RECEIPT`, `WASTAGE`) | T2.02 |
| `BusinessNumberGenerator.next(prefix)` | `ORD`, `JOB` numbers | done (M0) |
| `FinanceService.recordTransaction(...)` | expenses and drawings (T5.02) | T3.05 |
| `IdempotencyService`, `AuditService` | idempotent order delivery; audited reversals | T3.06 |
| `ProductionQueryService.requireBatch(UUID batchId)` → batch ref | optional `production_batch_id` on work records | T4.01 |
| `GET /api/v1/health` (public): `{"status":"UP","apiVersion":1,"time":"<ISO-8601 UTC>"}` | the client's first integration checkpoint | **A's first deliverable** (add with T1.06; until then the client uses `GET /actuator/health`) |
| All REST endpoints in `05-API-SPEC.md` for A's modules | the client | per A's milestones |

### 3.2 Provided by B, used by A
| Contract | Used by A for | Due with |
|---|---|---|
| `OrderService.lockForProduction(Collection<UUID> orderItemIds)` | production start (T4.03) | T3.03 |
| `OrderService.requireDeliverable(UUID orderItemId)` → `OrderItemRef` | production/outsourcing linking | T3.03 |
| `OrderQueryService.openObligations(UUID customerId)` → list of `{orderItemId, orderNumber, obligationDate, amount}` for orders in `CONFIRMED`..`COMPLETED` (PLAN Q2), oldest first | customer payment allocation (T3.07), outstanding (T3.10) | T3.04 |
| `OrderQueryService.requireObligation(UUID orderItemId)` → same record (404/422 when not payable) | explicit allocations (T3.08) | T3.04 |
| `OutsourcingQueryService.manufacturerObligations(UUID manufacturerId)` → `{jobId, jobNumber, obligationDate, amount = received kg × job rate}` | manufacturer payable/payments (T4.08) | T4.07 |
| `WorkRecordQueryService.workerObligations(UUID workerId)` → `{workRecordId, workDate, amount}` (RECORDED only) | worker payable/payments (T4.10) | T4.09 |
| REST endpoints for orders, outsourcing, work records, expenses in `05-API-SPEC.md` | the client, reports | per B's milestones |

Changing a contract after it is on `main` needs both developers to agree in the pull request.

## 4. Rules that prevent conflicts

1. **Branches.** `main` is the shared production branch where both developers' work is combined. Each developer works on
   their own branches: `feature/dev-a/<area>` and `feature/dev-b/<area>`. Merge into `main` only through a pull request reviewed
   by the other developer, after the milestone works and its tests pass. Never commit directly to `main`.
2. **Folders.** B owns `nexora-client/` completely. In `Nexora-backend/`, B owns only `com.nexora.orders`, `com.nexora.outsourcing`,
   `com.nexora.workforce`, `com.nexora.finance.expense` (expenses/drawings) and `src/test/java/com/nexora/contract/`, plus the
   matching test packages and migrations. Everything else in the backend is A's.
3. **Migration numbers are reserved** (from `04-DATA-MODEL.md §16`): A = V6 inventory, V7 procurement, V9 finance, V10 allocations/idempotency/audit,
   V11 production, V14 documents/notifications, V15 immutability triggers. **B = V8 orders, V12 outsourcing, V13 workforce.**
   A migration may be merged only when every lower version is already on `main` (Flyway refuses out-of-order versions).
   Expense columns live in A's V9; if B needs a change there, B asks A. Any **extra** migration: claim the next free number in the
   pull-request title first; whoever merges first keeps it.
4. **Shared files** (`shared/*`, `ErrorCode`, `Permission`, `build.gradle`, `application*.yml`, `GlobalExceptionHandler`, `docs/0*`,
   `DOMAIN_RULES.md`, `DECISIONS.md`): owned by A. B changes them only in a small separate pull request reviewed by A, never mixed
   into a feature branch.
5. **Docs are shared.** A business-rule change goes into `DOMAIN_RULES.md` (with a change-log row) and an architecture choice into
   `DECISIONS.md`, both reviewed by the other developer.
6. **Contract first.** Before building anything that depends on the other half, confirm in writing (pull request or chat):
   path, method, request DTO, response DTO, errors, permission, pagination/filters, state transitions. `05-API-SPEC.md` is the default
   answer; only deviations need discussion.
7. **No mocks in production code.** The client is tested against Ktor `MockEngine` with payloads copied from `05-API-SPEC.md`, but
   production code always calls the real API.

## 5. Order of work and integration checkpoints

| # | Checkpoint | A must have on `main` | B must have |
|---|---|---|---|
| 1 | Client shows "Backend connected" | `GET /api/v1/health` (or `/actuator/health`) | client M0 |
| 2 | Real login from the client | T1.01–T1.07 | client M1 |
| 3 | Parties screens on real data | T1.08–T1.13 | client M2 |
| 4 | Purchases + receipts from the client | T2.01–T2.10 | client M3, M4 |
| 5 | Orders end to end (needs inventory for delivery) | M2 | B backend T3.01–T3.04 + client M5 |
| 6 | Payments allocate to orders | T3.05–T3.13 (needs B's V8 merged first) | `OrderQueryService` |
| 7 | Production locks orders | T4.01–T4.04 | `OrderService.lockForProduction` |
| 8 | Outsourcing + workforce end to end | T4.08, payment part of T4.10 | B backend T4.05–T4.07, T4.09–T4.10 + client M7, M8 |
| 9 | Expenses from the client | T3.05 (`FinanceService`) | B backend T5.02 + client M9 |
| 10 | Dashboard, reports | M6 | client M10 |
| 11 | Release candidate | M7, M8 | client M11 (installers, E2E flows) |

## 6. Client technology (decision for both developers)

The first release is a **desktop application** (Windows MSI + Linux DEB). The client is **Kotlin + Compose Multiplatform** in
`nexora-client/` with **only the desktop target for now**; shared code lives in `commonMain` and platform code behind `expect`/`actual`
in `desktopMain`, so an Android target can be added later without a rewrite. **Android/mobile is future scope** (ADR-036). This replaces the desktop-only layout in `06-UI-SPEC.md §5.1` and milestone M9 of `07-TASKS.md`.
