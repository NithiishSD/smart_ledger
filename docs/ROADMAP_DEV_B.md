# Developer B Roadmap — SmartSilk (Nexora)

> **Current milestone: M0 — Client foundation** (next task: B0.1) · Last updated: 2026-10-08
>
> Work top to bottom. Tick `[x]` when a task meets its Definition of Done (§ at the end). Ownership and the contracts with
> Developer A are in `TEAM_SPLIT.md`; screens in `06-UI-SPEC.md`; endpoints in `05-API-SPEC.md`; backend task details
> (files, tests, requirement IDs) in `07-TASKS.md`; test names and traceability in `08-TESTING.md`.

## Your role
You build the **desktop client** (Kotlin + Compose Multiplatform, every screen; Android is future scope) and **three full backend
vertical slices** — customer orders, outsourcing, workforce — plus **expenses** as your finance cross-learning feature.
You do not become frontend-only: by the end you will have built entities, migrations, services, controllers, transactions,
locking-aware calls and integration tests yourself.

```
M0 Client foundation ─▶ M1 Login ─▶ M2 Parties ─▶ M3 Procurement ─▶ M4 Inventory
   ─▶ M5 Orders (FULL STACK) ─▶ M6 Production UI ─▶ M7 Outsourcing (FULL STACK) ─▶ M8 Workforce (FULL STACK)
   ─▶ M9 Finance UI + Expenses backend ─▶ M10 Dashboard, reports, notifications, documents ─▶ M11 Testing + release
```
Client milestones M0–M4 do not need any of Developer A's unfinished work (they use Ktor `MockEngine` with payloads from
`05-API-SPEC.md` until A's endpoints reach `main`, then switch to the real server). Each "Needs from A" line names what
must be on `main` before the **integration** step of that milestone.

---

## M0 — Client foundation
**Goal:** a desktop app in `nexora-client/` (Kotlin Multiplatform layout, desktop target only) that opens a window, keeps the shared
architecture in `commonMain`, and shows "Backend connected".
**Needs from A:** `GET /api/v1/health` (until it exists, call `GET /actuator/health`).

- [ ] **B0.1 Project setup** — Gradle build in `nexora-client/` (own wrapper): Kotlin Multiplatform + Compose Multiplatform with only
  the `desktop` (JVM 21) target; source sets `commonMain`, `desktopMain`, `commonTest`, `desktopTest` (no Android target now — ADR-036).
  Use the versions in `06-UI-SPEC.md §5.3`. Verify: `./gradlew :composeApp:run` opens a window and `./gradlew check` passes. (07 T9.01)
- [ ] **B0.2 Exact numbers and formatting** — `expect`/`actual typealias` for `BigDecimal`, a JSON serializer that never passes through
  `Double`, Indian money format (`₹12,50,000.50`), kg with 3 decimals, `dd-MM-yyyy` dates. Tests in `commonTest`. (T9.02, NFR-USE-03)
- [ ] **B0.3 API client** — one shared Ktor client (base URL from settings, JSON, timeouts), ProblemDetail → `ApiError(code, message,
  fieldErrors)`, error-code → user message table (`06 §1`), `ApiResult` (Success/Failure). Never call Ktor from a composable. (T9.03)
- [ ] **B0.4 UI state + navigation + theme** — `UiState` (Idle, Loading, Success, Error), ViewModel base on StateFlow, Koin modules,
  navigation shell skeleton, theme, strings in `strings.properties` (every visible string, Tamil later). (T9.06 partly)
- [ ] **B0.5 Health screen** — server URL setting (`expect`/`actual AppSettingsStore`), calls health, shows "Backend connected" / clear error.
  Verify against A's running backend. (T9.04 partly)

## M1 — Authentication (client)
**Needs from A:** T1.01–T1.07 (login, refresh, logout, `/auth/me`, `/actuator/info` api-version).
**Learn (do not build):** read A's security code — JWT access token, opaque refresh token with rotation, BCrypt, filter chain,
permissions, 401 vs 403. Explain each in `docs/LEARNING_NOTES_DEV_B.md`.

- [ ] **B1.1 Server setup + version check** — first-run screen; block login when the API major version differs. (S-AUTH-01, T9.04)
- [ ] **B1.2 Login screen** — `LoginScreen → AuthViewModel → AuthRepository → AuthApi`; validation, loading, error (401 INVALID_CREDENTIALS,
  429 RATE_LIMITED), success. (S-AUTH-02)
- [ ] **B1.3 Token handling** — access token in memory; refresh token through `expect`/`actual SecureTokenStore` (desktop actual: OS credential
  store via java-keyring); one shared refresh on 401, single retry,
  then back to login; logout. (S-AUTH-03, T9.05)
- [ ] **B1.4 Protected navigation + permission-based menu** — menu built from `/auth/me` permissions; 403 shows a clear message. (T9.06)

## M2 — Parties (client) + contract tests
**Needs from A:** T1.08–T1.13.
- [ ] **B2.1 Reusable components** — data table with paging/sort, search with 300 ms debounce, party picker, status chip, confirm dialog,
  error banner, empty state; keyboard and focus rules. (T9.07, NFR-USE-06)
- [ ] **B2.2 Party list, form, detail** — one reusable UI for all roles with a role filter; create, edit, add role, activate/deactivate.
  The client never computes balances. (S-PTY-01..03, T9.09)
- [ ] **B2.3 Backend cross-learning: party API contract tests** — `src/test/java/com/nexora/contract/PartyApiContractTest.java`: assert
  A's party endpoints return exactly the fields and error codes in `05-API-SPEC.md` (tests only; you own the `contract` package).

## M3 — Procurement (client) + contract tests
**Needs from A:** T2.07–T2.10.
- [ ] **B3.1 Purchase list, create, detail** — supplier picker, ordered kg, rate, cash/credit, credit days, due date and status **from the
  backend**. (S-PUR-01..03, T9.10)
- [ ] **B3.2 Receive and return dialogs** — partial receipts, accepted/rejected kg, remaining kg shown from the backend, receipt history;
  Idempotency-Key per submit, reused on retry. (S-PUR-04..06)
- [ ] **B3.3 Contract tests** — `PurchaseApiContractTest` (one purchase, two receipts, remaining quantity, 409 PURCHASE_RECEIPT_EXCEEDED).

## M4 — Inventory (client)
**Needs from A:** T2.01–T2.06.
- [ ] **B4.1 Stock summary + movement history** — product/location filters; values only from the backend. (S-INV-01..02, T9.11)
- [ ] **B4.2 Wastage, adjustment, opening stock dialogs** (INVENTORY_ADJUST only). (S-INV-03..04)
- [ ] **B4.3 External WIP view** — per manufacturer/job; shows an empty state until outsourcing exists (M7). (S-INV-05)

## M5 — Customer orders (FULL STACK, you own backend + client)
**Needs from A:** T1.12 (`PartyService`), T2.02 (`InventoryService`) on `main`. Your migration is **V8** (`TEAM_SPLIT.md §4`).
**Backend** — follow `07-TASKS.md` exactly, including its tests and requirement IDs:
- [ ] **B5.1** = **T3.01** Orders schema (`V8`) and `CustomerOrder` aggregate (items, warp specification, rate copied, amount =
  round(kg × rate, 2, HALF_UP), explicit state methods, `canModify()`).
- [ ] **B5.2** = **T3.02** Create, view and list orders.
- [ ] **B5.3** = **T3.03** Edit, confirm, cancel, ready, complete — plus the `OrderService.lockForProduction` / `requireDeliverable`
  contract for A's production (`TEAM_SPLIT §3.2`).
- [ ] **B5.4** = **T3.04** Deliver (DELIVERY / DIRECT_SALE movements through `InventoryService`, idempotent) — plus
  `OrderQueryService.openObligations` / `requireObligation` for A's payments (Q2: obligations start at confirmation).
**Client:**
- [ ] **B5.5** Order list, create (multiple items), detail with status actions and locked indicator; no free status editing. (S-ORD-01..03, T9.12)
**Integration:** customer → multi-item order → edit before production → confirm → (after A's T4.03) start production → order locked.

## M6 — Production (client) + contract tests
**Needs from A:** T4.01–T4.04 (production backend; it calls your `lockForProduction`).
- [ ] **B6.1** Batch list, create, start, complete (input/output/wastage/discrepancy), cancel, linked order items. The client never "fixes"
  input = output + wastage + discrepancy; it shows 422 PRODUCTION_NOT_RECONCILED. (S-PRD-*, part of T9.16)
- [ ] **B6.2** `ProductionApiContractTest` (start locks orders; reconciliation rejection).

## M7 — Outsourcing (FULL STACK)
**Needs from A:** `InventoryService`, `PartyService`. Your migration is **V12** (after A's V11 is on `main`).
- [ ] **B7.1** = **T4.05** Outsourcing schema (`V12`) and `OutsourcingJob`.
- [ ] **B7.2** = **T4.06** Create and issue (RAW_STOCK → EXTERNAL_WIP in one transaction; vuda rejected).
- [ ] **B7.3** = **T4.07** Receive, view, cancel, external WIP — plus `OutsourcingQueryService.manufacturerObligations` for A.
- [ ] **B7.4** Client: job list, create, issue, receive, detail, external WIP. (S-OUT-*, part of T9.16)
**Integration:** issue raw silk → external WIP up → receive warp → WIP down, finished stock up (A's inventory screens confirm it).

## M8 — Workforce (FULL STACK)
**Needs from A:** `PartyService`; `ProductionQueryService.requireBatch` (optional link). Your migration is **V13**.
- [ ] **B8.1** = **T4.09** Workforce schema (`V13`) and work records (ROLLING = hours, WARPING = kg, rate copied into the record —
  January stays ₹50/kg when the worker's rate becomes ₹55 in February).
- [ ] **B8.2** = work-record parts of **T4.10**: list with filters, reversal (RECORDED → REVERSED) — plus
  `WorkRecordQueryService.workerObligations` for A (A builds worker payable and payments).
- [ ] **B8.3** Client: work record entry, history, worker payable view (data from A's payable endpoint). (S-WRK-*, part of T9.16)

## M9 — Finance (client) + expenses backend
**Needs from A:** T3.05–T3.13, T5.01, T5.03–T5.05.
- [ ] **B9.1** = **T5.02** Expenses and personal drawings backend, in package `com.nexora.finance.expense` (money only through A's
  `FinanceService.recordTransaction`; idempotent; drawings excluded from business totals).
- [ ] **B9.2** Client: payments (customer/supplier/manufacturer/worker) with allocation preview from the backend, outstanding views.
  (S-PAY-*, T9.13)
- [ ] **B9.3** Client: accounts, transactions, transfers, adjustments, cash position. (S-FIN-*, T9.14)
- [ ] **B9.4** Client: expenses, loans, chits. (S-EXP, S-LON, S-CHT, T9.15)

## M10 — Dashboard, reports, notifications, documents (client)
**Needs from A:** M6 backend.
- [ ] **B10.1** Dashboard: cards from `GET /dashboard/summary` only; refresh; partial-failure state. (S-DASH-01, T9.08)
- [ ] **B10.2** Reports viewer + CSV export; notifications; document upload. (S-RPT, S-NTF, S-DOC, T9.17)
- [ ] **B10.3** Offline/timeout states and settings screen. (T9.18)

## M11 — Testing and release (client)
- [ ] **B11.1** Client tests complete per `08-TESTING.md §14` (view-model, repository with MockEngine, UI tests with `runComposeUiTest`,
  accessibility); all shared code stays in `commonMain`.
- [ ] **B11.2** End-to-end flows against a real backend + PostgreSQL: login; supplier → purchase → receipt → stock; customer → order →
  production → locked; outsourcing issue/receive; work record → payable; payment → outstanding down; dashboard updates.
- [ ] **B11.3** Installers: MSI (Windows) and DEB (Linux) via `nativeDistributions`. (T9.19)
- [ ] **B11.4** Client CI: `.github/workflows/client.yml` — build + tests on `ubuntu-latest` (UI tests under `xvfb-run`) and
  `windows-latest` (MSI). (T9.20)

---

## Definition of Done (every task)
**Client:** screen + ViewModel + repository + API client + UI state; loading, empty, error and success states; input validation mirroring
`05`; shared code in `commonMain` (only platform code in `desktopMain`, behind `expect`/`actual`); no API calls inside composables; no hard-coded business values;
values like stock, balances and outstanding only from the backend; tests green (`./gradlew check` in `nexora-client/`).
**Backend (your slices):** migration with constraints + FK indexes; entity with named state methods and no setters; DTO records with
validation; thin controller with `@PreAuthorize`; `@Transactional` application service; cross-module calls only through contracts;
the tests named in `07`/`08` (including rejection tests) green with `./gradlew test`; API matches `05-API-SPEC.md`.
**Integration:** request/response match the agreed contract; works end to end against PostgreSQL; no breaking change without agreement.
**Git:** `feature/dev-b/<area>` → pull request → reviewed by Developer A → `main` (the shared production branch). Conventional Commits. No secrets.
