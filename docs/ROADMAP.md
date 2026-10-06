# Implementation Roadmap — SmartSilk (Nexora)

> Covers SDLC **Step 9 Implementation → Step 10 Integration → Step 11 Testing/Security/Reliability → Step 12 Deployment/Monitoring/Docs**.
> Steps 1–8 (business → API design) are complete in `smart-ledger-docs/`.
>
> **Current phase: Phase 0 — Foundation** (next task: 0.2 `application.yml` + profiles) · Last updated: 2026-10-02
>
> How to use: work top to bottom and tick `[x]` as you finish tasks. Run `/next-task` to get the next task explained. Run `/task-done` to verify and tick it.

## Guiding strategy

1. **Vertical slices, not horizontal layers.** Each phase delivers working, tested endpoints end-to-end (migration → entity → service → API → tests). Don't build all entities first.
2. **Foundation before features.** Error handling, auth, numbering, auditing and test infrastructure are built once, in Phase 0/1, so every later module reuses them.
3. **Riskiest correctness first.** Inventory locking and payment allocation (Phases 2–3) are the hard, high-value parts. Prove them early.
4. **The dependency order follows the domain:** Parties → Procurement → Inventory → Orders → Payments → Production/Outsourcing → Finance extras → Visibility.
5. **Each phase ends with a demo-able state and a merge to `main`.**

```
P0 Foundation ─▶ P1 Identity+Parties ─▶ P2 Procurement+Inventory ─▶ P3 Orders+Payments (MVP slice ✔)
                                                                         │
     ┌───────────────────────────────────────────────────────────────────┘
     ▼
P4 Production+Outsourcing+Workforce ─▶ P5 Finance complete ─▶ P6 Dashboard/Reports/Notifications
     ─▶ P7 Hardening (Step 11) ─▶ P8 Deploy + Go-live (Step 12)

Track B (Android, Kotlin): starts after P3 ── F1…F6 in parallel with P4–P8
```

---

## Phase 0 — Foundation & engineering setup
**Goal:** A clean skeleton where every future feature has a place and a pattern.
**Why first:** Retrofitting error handling, auth or test infrastructure into 10 modules is painful.

- [x] 0.1 Rename base package `com.Nexora.Nexora_backend` → `com.nexora` and the main class → `NexoraApplication` (Java packages are lowercase; this is cheap now and costly later)
- [x] 0.2 Convert config to `application.yml` + profiles `dev` / `test` / `prod`. DB creds and JWT secret come from env vars with dev defaults. Set `hibernate.jdbc.time_zone=UTC`
- [x] 0.3 Move `docker-compose.yml` to the repo root (or keep it, but document it). Add a `.env.example`
- [x] 0.4 `shared/`: `AuditableEntity` (UUID id, createdAt, updatedAt, createdBy, version) + JPA auditing + `AuditorAware`
- [x] 0.5 `shared/`: `ErrorCode` enum, `BusinessException` hierarchy, `GlobalExceptionHandler` (ProblemDetail + `code`) — see CONVENTIONS §7
- [ ] 0.6 `shared/`: `PageResponse<T>`, `Clock` bean (Asia/Kolkata), money/weight helpers (scale + HALF_UP)
- [ ] 0.7 `shared/numbering`: `business_number_sequences` migration + `BusinessNumberGenerator.next("PUR")` → `PUR-2026-0001` (row lock, per-year reset) + concurrency test
- [ ] 0.8 springdoc-openapi, Swagger UI in dev only
- [ ] 0.9 Test infrastructure: Testcontainers Postgres, `AbstractIntegrationTest`, and the context-load test running against the container
- [ ] 0.10 CI: GitHub Actions workflow `./gradlew build` on every PR (Testcontainers works on GitHub runners)
- [ ] 0.11 Hygiene: `.editorconfig`, plus an optional formatter (Spotless + google-java-format) so style is never a review topic

**Exit criteria:** `./gradlew build` is green locally and in CI. A test endpoint that throws a `BusinessException` returns a correct ProblemDetail. Business number generation is concurrency-safe.
**Concepts you'll learn:** Spring profiles, JPA auditing, `@RestControllerAdvice`, RFC 9457, Testcontainers, CI.

---

## Phase 1 — Identity & Parties
**Goal:** Log in securely and manage customers, suppliers, manufacturers and workers.
**Why now:** Every business operation needs an authenticated user (for `created_by`) and parties.

### 1A Identity & security (ADR-014)
- [ ] 1.1 Migration: `users` (fix: `updated_at NOT NULL`, `version`, `last_login_at`), `roles`, `permissions`, `role_permissions`, `user_roles`, `refresh_tokens(token_hash, user_id, expires_at, revoked_at)`
- [ ] 1.2 `Permission` enum (`PARTY_MANAGE`, `PURCHASE_CREATE`, `PURCHASE_VIEW`, `INVENTORY_VIEW`, `ORDER_MANAGE`, `PAYMENT_CREATE`, `EXPENSE_CREATE`, `REPORT_VIEW`, `USER_MANAGE`, `INVENTORY_ADJUST`, …). Seed `OWNER` (all) and `STAFF`
- [ ] 1.3 Bootstrap the first OWNER from env vars on startup (only if no users exist). Never commit a real password
- [ ] 1.4 `SecurityFilterChain`: stateless, CSRF off for the API, `/api/v1/auth/**` + health public, everything else authenticated. `@EnableMethodSecurity`
- [ ] 1.5 `POST /auth/login` → access JWT (15 min) + refresh token. `POST /auth/refresh` (rotation). `POST /auth/logout` (revoke)
- [ ] 1.6 JSON 401/403 via the entry point + access-denied handler, in the same ProblemDetail format
- [ ] 1.7 Login brute-force protection (simple: lock the account or delay after N failures)
- [ ] 1.8 Tests: wrong password → 401 `INVALID_CREDENTIALS`, expired token → 401, missing permission → 403, refresh rotation (the old token no longer works)

### 1B Parties
- [ ] 1.9 **Revise the untracked `V3__initial_foundation_tables.sql`** before it is ever merged:
  - add `party_code` (UNIQUE, e.g. `CUS-0001`/`PTY-0001`), `status` + CHECK, `version`, `created_by`, `created_at NOT NULL`
  - relax address columns to nullable (a worker may have no pincode). Keep `name NOT NULL`
  - `party_roles.role` CHECK (`CUSTOMER`,`SUPPLIER`,`MANUFACTURER`,`WORKER`)
  - `party_phone_numbers`: index on `party_id`, partial unique index "one primary per party"
  - supplier `default_credit_days` (Rule 17) and worker `worker_type` / `default_rate` (profile columns or small role-profile table — decide and log it in DECISIONS)
  - indexes on FKs and on `lower(name)` for search
  - If V3 already ran on your local DB: `docker compose down -v` and re-run (it's unmerged, so editing is allowed)
- [ ] 1.10 `POST /parties`, `GET /parties/{id}`, `GET /parties?role=&search=&status=&page=`, `PATCH /parties/{id}`, `POST /parties/{id}/deactivate` / `activate`
- [ ] 1.11 `PartyService.requireActiveWithRole(id, role)` — **the method other modules will call** (throws `INVALID_PARTY_ROLE` / `PARTY_INACTIVE`)
- [ ] 1.12 Tests: create with multiple roles, search, deactivate, role check

**Exit criteria:** From Swagger/Postman you can log in, create a supplier and a customer, search, and deactivate. Unauthenticated calls get 401.
**Concepts:** Spring Security filter chain, JWT, BCrypt, refresh rotation, method security, many-to-many via join table.

---

## Phase 2 — Procurement & Inventory ledger (hardest correctness, do it carefully)
**Goal:** Purchase → partial/full receipt → returns → raw-silk stock that can never go negative.

- [ ] 2.1 Migration: `purchases`, `material_receipts`, `supplier_returns`, `material_movements`, `inventory_balances(product_type, location, quantity_kg, version, UNIQUE(product_type, location), CHECK quantity_kg >= 0)`
- [ ] 2.2 `inventory` module: `InventoryService.move(product, from, to, qty, reason, reference)` — **the only way stock changes.** Locks balance rows (`PESSIMISTIC_WRITE`), checks, writes the movement and the balance update. No public write endpoint
- [ ] 2.3 `Purchase` entity: `recordReceipt(received, accepted, rejected)` enforces Σ received ≤ ordered + status transitions. `due_date` computed
- [ ] 2.4 `POST /purchases`, `GET /purchases` (filters: supplier, status, date range), `GET /purchases/{id}` (with receipts, received/remaining)
- [ ] 2.5 `POST /purchases/{id}/receipts` — one transaction: receipt + RECEIPT movement (accepted kg) + purchase status
- [ ] 2.6 `POST /purchases/{id}/returns` — PURCHASE_RETURN movement, cannot exceed what is available
- [ ] 2.7 `GET /inventory/summary`, `GET /inventory/movements?product=&location=&from=&to=&page=`
- [ ] 2.8 **Concurrency test:** stock 100 kg, two parallel requests of 80 and 70 → exactly one succeeds, the other gets 409 `INSUFFICIENT_INVENTORY`, and the final balance is 20 or 30
- [ ] 2.9 Rebuild check: an admin/test utility confirms `inventory_balances` == Σ movements
- [ ] 2.10 (Proposed) Add Spring Modulith `verify()` test to enforce module boundaries

**Expected behaviour:** ordered 250, received 100 → receive 80 ✔ (180 received, 70 remaining, stock +accepted). Receive 100 more ✘ `PURCHASE_RECEIPT_EXCEEDED`.
**Exit criteria:** The expected-behaviour table passes as integration tests, including the concurrency test.
**Concepts:** ledger vs projection, pessimistic locking, `@Lock`, lock ordering, state machines in entities.

---

## Phase 3 — Orders & Payments → **MVP vertical slice complete** (design doc §8.37)
**Before starting:** get answers to Q1, Q2, Q5, Q7 in `DOMAIN_RULES.md §8`.

- [ ] 3.1 Migration: `customer_orders`, `order_items`, `financial_accounts`, `financial_transactions`, `payments`, `customer_payment_allocations`, `supplier_payment_allocations`, `idempotency_keys`
- [ ] 3.2 Orders: `POST /orders` (with items), `GET /orders`, `GET /orders/{id}`, `PATCH /orders/{id}` (→ `ORDER_LOCKED` when locked), `POST /orders/{id}/confirm|cancel|deliver`
- [ ] 3.3 Delivery creates DELIVERY / DIRECT_SALE movements from FINISHED_STOCK / RAW_STOCK (via InventoryService)
- [ ] 3.4 Finance accounts: `POST/GET /finance/accounts`, opening balance as an `OPENING_BALANCE` transaction. `GET /finance/accounts/{id}/transactions`
- [ ] 3.5 `FinanceService.recordTransaction(...)` — **the only way money moves** (like InventoryService)
- [ ] 3.6 `POST /payments` (direction IN from a customer): oldest-first auto-allocation or validated explicit allocations. Excess → advance. One transaction
- [ ] 3.7 Supplier payments (direction OUT) with oldest-first allocation to purchases. `GET /suppliers/{id}/payables`
- [ ] 3.8 `GET /customers/{id}/outstanding` (SQL aggregation)
- [ ] 3.9 Idempotency filter/aspect on `POST /payments` (+ receipts) with the `Idempotency-Key` header. Tests: replay → same response, no duplicate rows
- [ ] 3.10 Payment reversal: `POST /payments/{id}/reverse` → compensating transaction + allocations released
- [ ] 3.11 Tests: allocation example (A 20k, B 30k, C 40k; pay 45k → A settled, B 25k, B remaining 5k), locked order, over-allocation rejection

**Exit criteria (MVP slice):** Login → supplier → purchase → receive → inventory → customer → order → payment → outstanding, all via the API, all tested.
**Concepts:** aggregate with children (cascade/orphanRemoval), allocation algorithms, idempotency, reversal vs update, `@Version`.

---

## Phase 4 — Production, Outsourcing, Workforce
- [ ] 4.1 Migration: `production_batches` (+ `discrepancy_weight_kg`), `production_order_items`, `outsourcing_jobs`, `outsourcing_order_items`, `work_records`, `manufacturer_payment_allocations`, **`worker_payment_allocations`** (missing in the schema doc)
- [ ] 4.2 Production: `POST /production-batches`, `/{id}/start` (CONSUMPTION, locks order items), `/{id}/complete` (reconciliation, PRODUCTION_OUTPUT + WASTAGE), `/{id}/cancel`
- [ ] 4.3 Starting production marks linked orders `IN_PROGRESS` + locked (orders module method, not direct table access)
- [ ] 4.4 Outsourcing: create, `/{id}/issue` (OUTSOURCE_ISSUE), `/{id}/receive` (OUTSOURCE_RECEIPT + loss), `GET /inventory/external-wip` per manufacturer/job
- [ ] 4.5 Work records: `POST/GET /work-records` (ROLLING→hours, WARPING→kg, rate snapshot). `GET /workers/{id}/payable`
- [ ] 4.6 Manufacturer and worker payments reuse the Phase 3 payment + allocation engine
- [ ] 4.7 Tests: 100 in / 94 out / 4 waste / 2 discrepancy ✔. 100/94/10 ✘ `PRODUCTION_NOT_RECONCILED`. Vuda into production ✘

---

## Phase 5 — Finance completion
**Before starting:** Q4, Q6.
- [ ] 5.1 Expenses: `POST/GET /expenses` (category, business/personal, monthly/one-time — **columns missing in the schema doc, add them**) + reversal
- [ ] 5.2 Transfers: `POST /finance/transfers` (lock both accounts in id order, OUT+IN, not revenue)
- [ ] 5.3 Loans: create (LOAN_RECEIVED), principal repayment, interest payment. Outstanding derived
- [ ] 5.4 Chits: create, contribution / payout (cash-only rule)
- [ ] 5.5 Personal drawings (`PERSONAL_DRAWING` transaction type)
- [ ] 5.6 `GET /finance/transactions` with filters. `GET /finance/cash-position`
- [ ] 5.7 Tests: cash cannot go negative, transfer atomicity, chit non-cash rejected

---

## Phase 6 — Visibility: dashboard, reports, notifications, documents (P0 dashboard + P1 items)
- [ ] 6.1 `reporting` module: `GET /dashboard/summary` — one call covering cash, banks, receivables, payables, raw/finished stock, external WIP, pending orders/production/jobs, upcoming supplier dues, recent transactions. SQL projections only
- [ ] 6.2 Reports: customer outstanding, supplier payables (aging), purchases, sales/orders, expenses, inventory movements, outsourcing, cash/bank. All paginated and date-filtered
- [ ] 6.3 Verify query plans (`EXPLAIN ANALYZE`) and add indexes where needed
- [ ] 6.4 Notifications: `notification_configurations`, a `@Scheduled` job for supplier dues (day-8 / day-10 / overdue), `notifications` table with status. `NotificationProvider` interface with a log/no-op implementation for now
- [ ] 6.5 Documents: upload a bill/statement (local disk or S3-compatible behind a `FileStorage` interface), with size/type limits and stored metadata only in the DB

---

## Phase 7 — Hardening (SDLC Step 11: Testing, Security, Reliability)
- [ ] 7.1 Coverage review: every DOMAIN_RULES rule has a rejecting test. JaCoCo report in CI
- [ ] 7.2 Security review against design §8.8.23: no secrets or tokens in logs, CORS policy, request size limits, dependency vulnerability scan (OWASP dependency-check or GitHub Dependabot), `/security-review`
- [ ] 7.3 Audit: `created_by` everywhere, audit log for reversals/adjustments and user management
- [ ] 7.4 Observability: structured JSON logs, request-id filter, Actuator health/metrics secured, Prometheus endpoint
- [ ] 7.5 Performance smoke test with realistic data volume (e.g. 5 years of transactions generated)
- [ ] 7.6 Failure drills: DB restart mid-request, duplicate submits, expired tokens
- [ ] 7.7 API contract freeze v1: export `openapi.json` into the repo

---

## Phase 8 — Deployment & go-live (SDLC Step 12)
- [ ] 8.1 Decide hosting (ADR-023) and write the decision down
- [ ] 8.2 Dockerfile (multi-stage, non-root, JRE only) or `bootBuildImage`. `docker-compose.prod.yml` (app + postgres + Caddy)
- [ ] 8.3 CD: GitHub Actions builds the image → GHCR → deploy (manual approval)
- [ ] 8.4 Secrets via env/secret store. Separate prod DB user with least privilege (not `postgres`)
- [ ] 8.5 **Backups:** nightly `pg_dump` off-site, 30-day retention. **Restore tested** and documented
- [ ] 8.6 Monitoring/alerting: uptime check on `/actuator/health`, disk space, backup success
- [ ] 8.7 **Go-live data migration:** import opening stock (OPENING_BALANCE movements), account balances, customer receivables, supplier payables, active loans/chits from the notebooks. *Design needed:* how opening receivables/payables become allocatable obligations (e.g. an `opening_obligations` table). Reconcile totals with the owner and have them sign off
- [ ] 8.8 Runbook (`docs/RUNBOOK.md`): deploy, rollback, restore, rotate JWT secret, add a user
- [ ] 8.9 Release `v1.0.0` tag + CHANGELOG. Train the owner. Run in parallel with the notebook for 2–4 weeks before trusting it alone

---

## Track B — Android client (Kotlin), starts after Phase 3
- [ ] F1 Project setup: Compose, Hilt, Retrofit/OkHttp, kotlinx.serialization, module structure, environment config
- [ ] F2 Auth: login screen, encrypted token storage, 401 → refresh → retry `Authenticator`, logout
- [ ] F3 Error handling: map `code` → user-friendly messages (Tamil/English?), offline/timeout states, idempotency key per submit
- [ ] F4 Screens for the MVP slice: parties, purchase + receive, inventory, order, payment, outstanding
- [ ] F5 Dashboard screen (the owner's main screen)
- [ ] F6 Production, outsourcing, work records, expenses, transfers, reports
Principles: minimal taps for daily entries (NFR 20.5), big readable numbers, never compute business values on the device.

---

## Known design gaps (resolve when the phase arrives, then record in DECISIONS/DOMAIN_RULES)
| Gap | Phase |
|---|---|
| Party schema in V3 diverges from the design doc (multi-role, structured address). Keep V3's approach, but add code/status/credit days | 1 |
| Supplier default credit days and worker type/rate have no column in the schema doc | 1 |
| `worker_payment_allocations` missing from the schema doc | 4 |
| Expense business/personal + monthly/one-time flags missing | 5 |
| `financial_transactions` has many nullable FKs — add a CHECK that exactly one reference (or none for manual/opening) is set | 3 |
| Opening receivables/payables representation for go-live | 8 (design in 3) |
| Delivery/sale billing (invoice numbers, GST later) is out of MVP, but numbering is ready | post-MVP |
| Open business questions Q1–Q8 | see DOMAIN_RULES §8 |

## Post-MVP backlog (P2/P3, don't start before v1.0 is live)
Monthly/yearly P&L · product/customer profitability · FIFO/lot costing · WhatsApp reminders & bill sharing · Voice AI (confirmation-based, read-only first) · AI advisory · forecasting · multi-user staff roles.
