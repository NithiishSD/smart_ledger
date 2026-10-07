# 08 - Testing

How the SmartSilk (Nexora) backend and desktop client are tested: levels, tools, layout, fixtures, data isolation, coverage, the end-to-end scenarios,
the concurrency tests and their mutation checks, and the traceability from every requirement in `02-REQUIREMENTS.md` to a test.
Test and task names are identical to `07-TASKS.md`.

## 1. Principles

1. **Real database.** Every test that touches persistence runs on PostgreSQL 17 in Testcontainers (`postgres:17-alpine`). H2 or any
   in-memory database is never used (ADR-015, NFR-TEST-01): locking, `NUMERIC`, CHECK constraints and triggers must behave as in production.
2. **Every rule is proven by rejection.** For each business rule there is a test that sends the bad input and asserts the HTTP status and
   the stable `code` (NFR-TEST-04).
3. **Every lock is proven by a multi-thread test that fails without the lock** (NFR-TEST-05, section 9).
4. **Tests own their data.** No test depends on another test's data or on execution order (NFR-TEST-09, section 6).
5. **Traceability is automatic.** A test's `@DisplayName` starts with the requirement IDs it proves; `RequirementTraceabilityTest` fails
   the build when an MVP requirement ID is not found in any display name (NFR-TEST-03).
6. **Data goes in through the front door.** Fixtures create records through application services or the HTTP API so that ledger
   invariants hold; raw SQL is used only to read results or to build a state that the API forbids on purpose.

## 2. Test levels

| Level | What it proves | Base class / tool | Location (under `src/test/java/com/nexora`) | Class suffix |
|---|---|---|---|---|
| Domain unit | entity invariants, every legal and illegal state transition, allocation and rounding logic | plain JUnit 6 + AssertJ, no Spring | `<module>/domain` | `Test` |
| Use-case integration | a service method end to end in one transaction against the real schema | `AbstractIntegrationTest` (`@SpringBootTest` + Testcontainers) | `<module>/application` | `IntegrationTest` |
| HTTP / API | status codes, validation, problem body and `code`, 401/403, idempotency headers, response shape | `AbstractApiTest` (MockMvc + real JWT from login) | `<module>/api` | `ApiTest` |
| Concurrency | locks: no negative stock or cash, no over-allocation, no duplicate numbers, no deadlock | `AbstractIntegrationTest` + `ConcurrentRunner` | `<module>/application` | `ConcurrencyTest` |
| Scenario ("end-to-end") | complete business loops across modules through the HTTP API | `AbstractApiTest` | `scenario` | `ScenarioTest` |
| Migration / schema | all migrations on an empty database with `ddl-auto=validate`, migration on previous-release data, FK indexes, triggers | `AbstractIntegrationTest`, Flyway API | `shared/persistence` | `IntegrationTest` / `Test` |
| Architecture / static | module boundaries, clock usage, exact types, SQL safety, permission names, error-code coverage, traceability | plain JUnit reading `src/main/java` and test sources | `architecture` | `Test` |
| Security | token lifecycle, lockout, headers, CORS, limits, logs, DB roles | `AbstractApiTest` / `AbstractIntegrationTest` | `shared/security`, `identity` | `ApiTest` / `Test` |
| Performance | p95 latency budgets, mixed load, start-up time on the reference dataset | `@Tag("performance")`, `RANDOM_PORT` + `java.net.http.HttpClient` | `performance` | `Test` |
| Operational checks | image, compose, CI duration, deploy/rollback, backup/restore, monitoring, release | commands listed in section 13 | not JUnit | `check:` |
| Desktop view-model / repository | UI state, validation, idempotency-key reuse, token refresh, error mapping, BigDecimal parsing, formatting | JUnit Jupiter + kotlinx-coroutines-test; Ktor `MockEngine` for the API client | `nexora-desktop/src/test/kotlin/com/nexora/desktop/{viewmodel,repository,api,util}` | `ViewModelTest` / `Test` |
| Desktop UI | main flows, keyboard operation, text scaling, accessible names | Compose Multiplatform `compose.uiTest` (`runComposeUiTest`) | `nexora-desktop/src/test/kotlin/com/nexora/desktop/ui` | `UiTest` |

## 3. Tools and versions

Versions are managed by the Spring Boot 4.1.1 BOM unless stated; read from `./gradlew dependencies --configuration testRuntimeClasspath`.

| Tool | Version | Use |
|---|---|---|
| JUnit Jupiter | 6.0.3 | test engine, `@DisplayName`, `@Tag`, class/method orderers |
| AssertJ | 3.27.7 | assertions (`isEqualByComparingTo` for `BigDecimal`) |
| Mockito | 5.23.0 | only to simulate a failing external collaborator (notification provider, file storage); business collaborators are real |
| Spring Boot Test | 4.1.1 | `@SpringBootTest`, `@AutoConfigureMockMvc` (`org.springframework.boot.webmvc.test.autoconfigure`) |
| Spring Security Test | 7.1.1 | request post-processors where a token is not the subject of the test |
| Testcontainers | 2.0.5 | `org.testcontainers.postgresql.PostgreSQLContainer`, image `postgres:17-alpine` |
| JaCoCo | 0.8.15 (added in T7.01) | line coverage and the 80 % gate |
| OWASP dependency-check | 13.0.0 (added in T7.02) | dependency vulnerability gate |
| Desktop: JUnit Jupiter, kotlinx-coroutines-test, Ktor client mock, Compose `uiTest` | 6.0.3, 1.11.0, 3.6.0, Compose Multiplatform 1.12.1 (pinned in `06-UI-SPEC.md` section 5.3) | client unit, API-client and UI tests (M9) |

## 4. Layout and naming

- Mirror the main package: a test for `com.nexora.finance.application.PaymentService` lives in `com.nexora.finance.application`.
- Method names: `method_condition_expectedResult`, for example `receive_beyondOrdered_returns409PurchaseReceiptExceeded`.
- Display names: requirement IDs first, then the method name in words:
  `@DisplayName("FR-PROC-08 NFR-TEST-04 receive beyond ordered returns409 purchase receipt exceeded")`.
- One test class per production class or per endpoint group; scenario classes per business loop.
- Money and weight assertions use `isEqualByComparingTo("151300.00")` (never `equals` on `BigDecimal`).
- Every rejection test asserts three things: HTTP status, `code`, and that nothing changed (no new movement/transaction row, balance unchanged).

## 5. Shared fixtures (`src/test/java/com/nexora/support`)

| Class | Responsibility |
|---|---|
| `AbstractIntegrationTest` (exists) | starts one `postgres:17-alpine` container per JVM (singleton pattern) and points `spring.datasource.*` at it with `@DynamicPropertySource`; Flyway migrates the empty database at context start |
| `AbstractApiTest` | extends `AbstractIntegrationTest`, adds `@AutoConfigureMockMvc`, `MockMvc`, JSON helpers and `AuthTokens`; calls `DatabaseCleaner.clean()` in `@BeforeEach` |
| `AuthTokens` | logs in the bootstrap owner (dev defaults) once per class and caches the access token; creates a STAFF user with a unique username through the repository and password encoder for 403 tests; can mint an expired token with the real `JwtEncoder` |
| `DatabaseCleaner` | `TRUNCATE ... CASCADE` of every business table (all modules, `business_number_sequences`, `idempotency_keys`, `refresh_tokens`), in one statement; never touches `flyway_schema_history`, `permissions`, `roles`, `role_permissions`, `users`, `user_roles` or seeded `notification_configurations` |
| `TestDataFactory` | builds valid records through application services: `supplier()`, `customer()`, `manufacturer()`, `worker()`, `lender()`, `cashAccount(opening)`, `bankAccount(opening)`, `purchase(...)`, `receive(...)`, `order(...)`, `confirmedOrder(...)`, `rawSilkInStock(kg)`; names get a short random suffix so parallel classes never collide |
| `ConcurrentRunner` | runs N callables in a fixed pool, holds them on a `ready` latch, releases them together with a `go` latch, waits at most 30 s, returns successes and exceptions separately; each callable runs in its own `TransactionTemplate` |

## 6. Data isolation and cleaning

- **Append-only tables cannot be cleaned with `DELETE`.** From V15 (T7.05) the ledger and history tables (`material_movements`,
  `financial_transactions`, receipts, returns, allocations, `opening_obligations`, `account_transfers`, `audit_log`) carry the
  `prevent_row_modification()` trigger that rejects `UPDATE` and `DELETE` (`04-DATA-MODEL.md` section 14). Integration and API tests
  therefore clean with `DatabaseCleaner` (`TRUNCATE ... CASCADE`, which does not fire row-level triggers) in `@BeforeEach`, or create
  unique data and assert only on their own rows.
- Tests that assert business numbers (`PUR-2026-0001`) rely on `DatabaseCleaner` having truncated `business_number_sequences`; the
  year comes from the injected `Clock`.
- Identity data is never truncated: the bootstrap owner exists for the life of the container; extra users get unique usernames.
- Avoid `@DirtiesContext` and per-class `@MockitoBean` (each creates another Spring context and slows the suite). A different profile
  (for example `OpenApiProdProfileTest` with `prod`) is the only accepted reason for a second context.
- **Order independence check:** `./gradlew test -PrandomOrder` sets `junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random`
  and `junit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random`; it must pass (NFR-TEST-09, M7 gate).

## 7. Coverage

- JaCoCo plugin 0.8.15 (T7.01). `jacocoTestReport` writes HTML and XML to `build/reports/jacoco/test/`.
- `jacocoTestCoverageVerification` restricts its class directories to `com/nexora/*/application/**` and `com/nexora/*/domain/**` and
  enforces `LINE COVEREDRATIO >= 0.80`; `check` depends on it, so `./gradlew build` fails below 80 % (NFR-TEST-02, ADR-029).
- Controllers, DTOs and configuration are covered by API tests but are not part of the ratio; the ratio measures business logic.
- Lowering the threshold or excluding a business class to pass the gate is not allowed; missing coverage is fixed with tests.

## 8. End-to-end scenarios (NFR-TEST-06)

All scenarios run through the HTTP API with a real token on a cleaned database. Opening balances are entered through the API.
NFR-TEST-06 loop (a) is covered by `MvpScenarioTest` plus `ProductionScenarioTest`; loop (b) by `OutsourcingScenarioTest`;
loop (c) by `FinanceScenarioTest`. Together they must finish within 3 minutes. Dashboard assertions are added to (a) in T6.01.

### 8.1 `MvpScenarioTest#loginSupplierPurchaseReceiveOrderDeliverPayOutstanding`

| Step | Request | Expected |
|---|---|---|
| 1 | login as owner | 200, token pair |
| 2 | cash account opening 100000.00; bank account opening 200000.00 | balances 100000.00 / 200000.00 |
| 3 | create supplier (credit days 10) and customer | 201, codes `PTY-...` |
| 4 | purchase RAW_SILK 250.000 kg at 850.00 | `PUR-<year>-0001`, value 212500.00, due date = purchase date + 10 |
| 5 | receipt 100.000 received / 98.000 accepted / 2.000 rejected | status `PARTIALLY_RECEIVED`; RAW_STOCK RAW_SILK 98.000 |
| 6 | receipt 80.000 / 80.000 / 0 | received 180.000, remaining 70.000; stock 178.000 |
| 7 | receipt 100.000 | 409 `PURCHASE_RECEIPT_EXCEEDED`; stock still 178.000 |
| 8 | order RAW_SILK 50.000 kg at 1000.00, confirm, ready, deliver | amount 50000.00; `DIRECT_SALE` 50.000; stock 128.000 |
| 9 | customer payment IN 30000.00 cash | allocated 30000.00; outstanding 20000.00; payment status `PARTIALLY_PAID`; cash 130000.00 |
| 10 | supplier payment OUT 51300.00 bank | supplier payable (Q1: 178 x 850 = 151300.00) becomes 100000.00; bank 148700.00 |
| 11 | `GET /customers/{id}/outstanding` | 20000.00, bucket 0-30 days |
| 12 (T6.01) | `GET /dashboard/summary` | cash 130000.00, bank 148700.00, receivables 20000.00, supplier payables 100000.00, raw silk 128.000 |

### 8.2 `ProductionScenarioTest#purchaseProduceDeliverPay`

| Step | Request | Expected |
|---|---|---|
| 1 | purchase and receive 100.000 kg RAW_SILK | RAW_STOCK 100.000 |
| 2 | customer order SILK_WARP 94.000 kg at 1200.00, confirm | amount 112800.00 |
| 3 | batch SILK_WARP input 100.000 linked to the item; start | RAW_STOCK 0.000; INTERNAL_WIP SILK_WARP 100.000; order `IN_PROGRESS`, `PATCH` -> 409 `ORDER_LOCKED` |
| 4 | complete output 94.000, wastage 4.000, discrepancy 2.000 | FINISHED_STOCK SILK_WARP 94.000; WIP 0.000 |
| 5 | work record WARPING 100.000 kg at worker default 2.50 | amount 250.00 |
| 6 | order ready, deliver | `DELIVERY` 94.000; finished stock 0.000 |
| 7 | customer payment 112800.00; worker payment 250.00 | order `PAID`; work record `PAID` |
| 8 (T6.01) | dashboard | receivables 0.00; finished stock 0.000 |

### 8.3 `OutsourcingScenarioTest#issueReceiveDeliverPayManufacturer`

| Step | Request | Expected |
|---|---|---|
| 1 | RAW_SILK 200.000 in stock; manufacturer; job SILK_WARP at 40.00/kg without order | `JOB-...`, status `CREATED` |
| 2 | issue | RAW_STOCK 0.000; EXTERNAL_WIP SILK_WARP 200.000; status `MATERIAL_ISSUED` |
| 3 | receive 185.000 with wastage 15.000 | FINISHED_STOCK SILK_WARP 185.000; job `COMPLETED`; external WIP 0.000 |
| 4 | `GET /manufacturers/{id}/payable` | 7400.00 |
| 5 | manufacturer payment 7400.00 | payable 0.00 |

### 8.4 `FinanceScenarioTest#transferExpenseLoanChitReversalCashPosition`

| Step | Request | Expected |
|---|---|---|
| 1 | cash opening 50000.00, bank opening 200000.00 | |
| 2 | transfer bank -> cash 20000.00 | cash 70000.00, bank 180000.00; not revenue |
| 3 | expense TRANSPORT 1500.00 cash; personal drawing 5000.00 cash | cash 63500.00; business expense total 1500.00 |
| 4 | lender party; loan 100000.00 into bank | bank 280000.00; `LOAN_RECEIVED` not revenue |
| 5 | principal repayment 10000.00; interest 1200.00 (bank) | outstanding principal 90000.00; bank 268800.00 |
| 6 | chit contribution 5000.00 cash; same with UPI | cash 58500.00; UPI -> 422 `INVALID_PAYMENT_METHOD` |
| 7 | reverse the transport expense | cash 60000.00; original transaction unchanged, one `REVERSAL` row |
| 8 | `GET /finance/cash-position` | cash 60000.00, bank 268800.00, total 328800.00 |

## 9. Concurrency tests and the mutation check

Each test uses `ConcurrentRunner` (all threads released at once) and asserts both the outcome and the final database state.

| Test | Set-up and action | Expected | Mutation check (remove, run, expect FAIL, restore) |
|---|---|---|---|
| `InventoryConcurrencyTest#parallel80And70From100_exactlyOneSucceeds` | RAW_STOCK 100.000; two threads take 80.000 and 70.000 | exactly one success, one 409 `INSUFFICIENT_INVENTORY`; final 20.000 or 30.000; reconciliation holds | `@Lock(PESSIMISTIC_WRITE)` on the balance query in `InventoryBalanceRepository` |
| `InventoryConcurrencyTest#oppositeTwoBalanceMovements_doNotDeadlock` | many threads moving A->B and B->A | all complete within 30 s, no PostgreSQL deadlock | lock rows in request order instead of (product, location) order |
| `BusinessNumberGeneratorTest#next_withManyParallelRequests_neverReturnsDuplicates`, `PurchaseNumberConcurrencyTest#parallelCreates_produceUniqueGapFreeNumbers` | 20 parallel requests | 20 distinct numbers 0001..0020 | `@Lock` on `findForUpdate` in `BusinessNumberSequenceRepository` (verified: fails with `ObjectOptimisticLockingFailureException`) |
| `CashConcurrencyTest#parallelCashOuts_neverDriveBalanceNegative` | cash 1000.00; two OUT of 800.00 | one success, one 409 `INSUFFICIENT_FUNDS`; balance 200.00 | account `FOR UPDATE` in `FinanceService` |
| `PaymentConcurrencyTest#parallelPayments_neverOverAllocate` | one order item of 10000.00; two payments of 8000.00 | allocations sum to 10000.00; the remainder is advance | obligation lock (ascending id) in `PaymentService` |
| `PaymentReversalConcurrencyTest#parallelReversals_reverseOnce` | two reversals of one payment | one `REVERSED`, one 409 `INVALID_STATE_TRANSITION`; one `REVERSAL` transaction | payment row lock / `@Version` check |
| `TransferConcurrencyTest#oppositeTransfers_doNotDeadlock` | A->B and B->A in parallel, 20 rounds | all complete, balances consistent, no deadlock | ascending-UUID lock order in `TransferService` |
| `ProductionConcurrencyTest#parallelStarts_cannotOversubscribeRawSilk` | RAW 100.000; two batches of 60.000 started together | one starts, one 409; the losing batch stays `PLANNED` | balance lock |
| `OutsourcingConcurrencyTest#parallelIssues_cannotOversubscribeRawSilk` | RAW 100.000; two issues of 60.000 | one issued, one 409 | balance lock |
| `IdempotencyConcurrencyTest#parallelDuplicates_createOnePayment` | same `Idempotency-Key` and body sent twice at once | one payment row; second response is the replay | unique index `(user_id, key)` on `idempotency_keys` |
| `LoginLockoutConcurrencyTest#parallelWrongPasswords_countEveryFailure` | 5 parallel wrong logins | account locked; counter 5 | user row lock in `UserRepository` |

The mutation check is performed at the milestone gates (M2, M3, M4, M5) and is never committed.

### 9.1 Idempotent routes

The 18 MVP routes of `03-ARCHITECTURE.md` section 7.2 require `Idempotency-Key`. One replay test per route group proves that the same key and
body returns the stored response (header `Idempotency-Replayed: true`) and creates no second row; `IdempotencyIntegrationTest` covers the
shared rules (different body -> 409 `DUPLICATE_REQUEST`, missing key -> 400 `VALIDATION_FAILED`, rolled-back use case leaves no key), and
`IdempotencyRouteCoverageTest` fails when the set of `@Idempotent` routes differs from the 18 listed.

| Route group | Routes | Replay test | Task |
|---|---|---|---|
| Procurement | `POST /purchases/{id}/receipts`, `POST /purchases/{id}/returns` | `PurchaseReceiptApiTest#receive_replayWithSameKey_createsOneReceipt`, `SupplierReturnApiTest#return_replayWithSameKey_createsOneReturn` | T3.12 |
| Orders | `POST /orders/{id}/deliver` | `OrderDeliveryApiTest#deliver_replayWithSameKey_deliversOnce` | T3.12 |
| Payments | `POST /payments`, `POST /payments/{id}/reverse` | `PaymentApiTest#record_replayWithSameKey_returnsStoredResponse`, `PaymentReversalApiTest#reverse_replayWithSameKey_returnsStoredResponse` | T3.12 |
| Outsourcing | `POST /outsourcing-jobs/{id}/receive` | `OutsourcingJobApiTest#receive_replayWithSameKey_receivesOnce` | T4.07 |
| Transfers | `POST /finance/transfers` | `TransferApiTest#transfer_replayWithSameKey_createsOneTransfer` | T5.01 |
| Expenses | `POST /expenses`, `POST /expenses/{id}/reverse` | `ExpenseApiTest#record_replayWithSameKey_createsOneExpense`, `ExpenseApiTest#reverse_replayWithSameKey_returnsStoredResponse` | T5.02 |
| Account adjustments | `POST /finance/accounts/{id}/adjustments` | `FinancialAccountApiTest#adjustment_replayWithSameKey_returnsStoredResponse` | T5.03 |
| Loans | `POST /loans`, `POST /loans/{id}/principal-repayments`, `POST /loans/{id}/interest-payments`, `POST /loans/{id}/payments/{transactionId}/reverse` | `LoanApiTest#moneyWrites_replayWithSameKey_returnStoredResponse` (parameterized over the four routes) | T5.04 |
| Chits | `POST /chits/{id}/contributions`, `POST /chits/{id}/payout`, `POST /chits/{id}/organiser-payments`, `POST /chits/{id}/entries/{transactionId}/reverse` | `ChitApiTest#moneyWrites_replayWithSameKey_returnStoredResponse` (parameterized over the four routes) | T5.05 |
| All 18 | the set above | `IdempotencyRouteCoverageTest#exactlyTheEighteenListedRoutes_requireIdempotencyKey` | T5.06 |

## 10. Security tests

- Token lifecycle: login, wrong/unknown/inactive user (same message), refresh rotation, expired refresh, reuse detection, logout (`AuthApiTest`).
- 401 vs 403 everywhere: `EndpointSecurityCoverageTest` iterates all registered handler methods, calls each without a token (expects 401
  problem+json) and with a STAFF token lacking the permission (expects 403 `ACCESS_DENIED`), except the public endpoints of FR-AUTH-12.
- `PreAuthorizePermissionTest`: every `hasAuthority('X')` names a `Permission` constant.
- Lockout (`UserTest`, `AuthApiTest`, `LoginLockoutConcurrencyTest`), BCrypt storage (`PasswordStorageIntegrationTest`).
- Headers, CORS, body and upload limits, rate limit (`SecurityHeadersApiTest`, `RequestLimitApiTest`, `RateLimitApiTest`, `DocumentApiTest`).
- Secrets and sensitive data: `SecurityPropertiesTest`, `ConfigurationFailFastTest`, `SensitiveDataLogTest` (captures log output with an
  in-memory appender and searches for the test password, the tokens and a full account number).
- `SqlSafetyTest` (no string-built native SQL), `DatabaseRoleIntegrationTest` (app role cannot update or delete ledger rows),
  `ImmutabilityTriggerIntegrationTest`.

## 11. Migration and schema tests

- `MigrationIntegrationTest#allMigrations_onEmptyDatabase_validateAgainstEntities`: every build migrates an empty database and the context
  starts with `ddl-auto=validate` (NFR-TEST-07); an entity mapped to a missing column fails the build.
- `MigrationIntegrationTest#migrationsApplyOnDataFromPreviousRelease`: Flyway migrates to the previous release version, loads
  `src/test/resources/db/previous-release-data.sql`, then migrates to the latest version and checks row counts and invariants (NFR-REL-07).
- `ForeignKeyIndexIntegrationTest`, `DerivedValuesTest`, `ImmutabilityTriggerIntegrationTest`, `InventoryReconciliationIntegrationTest`.

## 12. Performance tests

- Tagged `@Tag("performance")`; excluded from the default `test` task; run with `./gradlew test -Pperformance` at the M7 gate and before a release.
- `ReferenceDataGenerator` builds the 5-year workload through the services in batches: at least 100000 material movements and 100000
  financial transactions with consistent balances, in under 10 minutes (NFR-PERF-01).
- Measurement: application on a random port, `java.net.http.HttpClient`, 20 warm-up requests, then 200 sequential requests per endpoint;
  p95 computed from the sorted latencies.

| Budget | Requirement | Test |
|---|---|---|
| single record / one page (size 20), incl. inventory summary: p95 <= 300 ms | NFR-PERF-02 | `PerformanceSmokeTest#reads_withinP95Of300ms` |
| dashboard: p95 <= 500 ms | NFR-PERF-03 | `PerformanceSmokeTest#dashboard_withinP95Of500ms` |
| create/receive/deliver/payment: p95 <= 500 ms; payment over 20 obligations <= 800 ms | NFR-PERF-04 | `PerformanceSmokeTest#writes_withinP95Of500ms` |
| 20 clients mixed load: only documented 409s, no deadlock | NFR-PERF-05 | `PerformanceSmokeTest#twentyClientsMixedLoad_noUnexpectedFailures` |
| one-year report p95 <= 2 s; 50000-row CSV <= 10 s | NFR-PERF-06 | `PerformanceSmokeTest#yearReportAndCsvExport_withinBudgets` |
| start-up incl. migrations <= 60 s | NFR-PERF-08 | `StartupTimeTest#emptyDatabase_migratesAndStartsWithin60Seconds` |
| statements per list request independent of page size | NFR-PERF-07 | `PartyListQueryCountIntegrationTest#list_statementCount_doesNotGrowWithPageSize` (Hibernate statistics) |

## 13. Operational checks

Requirements about the build pipeline, the image and production operations are proven by commands rather than JUnit tests.
Each is run at the gate of the milestone that introduces it and again before the release.

| Check | Requirement | Task |
|---|---|---|
| `./gradlew jacocoTestCoverageVerification` | NFR-TEST-02 | T7.01 |
| `./gradlew test -PrandomOrder` | NFR-TEST-09 | T7.01 |
| `./gradlew dependencyCheckAnalyze` (fails on CVSS >= 7.0) | NFR-SEC-07 | T7.02 |
| image runs as a non-root user (`docker run --rm --entrypoint id nexora:local -u` prints a non-zero id) | NFR-OPS-01 | T8.01 |
| production compose validates and starts; HTTP redirects to HTTPS with HSTS | NFR-OPS-01, NFR-SEC-01 | T8.02 |
| CI finishes green within 15 minutes on a pull request | NFR-OPS-03 | T8.04 |
| redeploy of the previous image within 15 minutes | NFR-OPS-04 | T8.04 |
| backup + restore drill exit 0 with matching reconciliation totals | NFR-REL-04, NFR-OPS-05 | T8.05 |
| alert within 3 minutes of a stopped app; monthly availability >= 99.0 % | NFR-OBS-05, NFR-REL-03 | T8.06 |
| release tag and signed weekly reconciliation during the parallel run | NFR-OPS-07 | T8.09 |

## 14. Desktop client testing (M9)

The client is tested in its own Gradle build (`nexora-desktop/`); `cd nexora-desktop && ./gradlew check` runs everything below.

| Level | Tool | What is proven | Examples |
|---|---|---|---|
| ViewModel unit | JUnit Jupiter 6.0.3 + `kotlinx-coroutines-test` (`runTest`, `StandardTestDispatcher`, virtual time for the 300 ms search debounce) with fake repositories | state transitions, client-side validation mirroring the API, only legal actions shown, permission-based visibility, default values (today, last account), idempotency key reused on retry and discarded after 2xx/4xx | `RecordPaymentViewModelTest`, `OrderDetailViewModelTest`, `NavigationViewModelTest`, `SubmitControllerTest` |
| Repository / API client | Ktor `MockEngine` returning recorded JSON and ProblemDetail bodies | 401 -> exactly one shared refresh -> one retry; failed refresh clears the stored token; ProblemDetail mapped to `ApiError` with field errors; 30 s timeout; `BigDecimal` round trip `12500000.50` exact (never `Double`) | `AuthRepositoryTest`, `ApiClientTest`, `BigDecimalSerializerTest` |
| Formatting and resources | JUnit | `₹12,50,000.50`, `1,250.500 kg`, `dd-MM-yyyy`; every server `ErrorCode` has a user message | `IndianFormatTest`, `ErrorMessagesTest` |
| UI flows | `compose.uiTest` (`runComposeUiTest`) against fake repositories | login, record payment, receive silk and record work completed with the keyboard only; key figures at least 20 sp; text scale 200 % without clipped labels; icon-only buttons have accessible names; colour contrast at least 4.5:1 | `LoginUiTest`, `RecordPaymentUiTest`, `ReceiveSilkUiTest`, `RecordWorkUiTest`, `DashboardUiTest`, `AccessibilityUiTest` |
| Credential store | JUnit with an in-memory keyring adapter | only the refresh token is stored; nothing written to the settings file contains a token or password | `CredentialStoreTest` |
| Packaging | `./gradlew packageDistributionForCurrentOS` | DEB on Linux, MSI on Windows | M9 gate |

Rules:
- The client never computes business values (NFR-USE-02), so client tests assert that figures shown equal the figures in the mocked API
  response; amounts, totals, allocations and balances are never derived in a test expectation from other fields.
- UI tests run headless on Linux CI under `xvfb-run` and natively on `windows-latest` (T9.20).
- Client tests carry the same display-name rule as the backend (requirement IDs first), so `RequirementTraceabilityTest` reads both test trees.
- End-to-end checks of the client against a real server are manual at the M9 gate (install, connect to staging, log in, record a payment,
  receive silk, record work); the server behaviour itself is covered by the backend scenarios in section 8.

## 15. Decisions made while writing

| # | Decision | Reason |
|---|---|---|
| 1 | Test class suffixes `Test` (unit/static), `IntegrationTest`, `ApiTest`, `ConcurrencyTest`, `ScenarioTest`; desktop `ViewModelTest` and `UiTest`. | Level is visible from the name; Gradle runs them all with `useJUnitPlatform()`. |
| 2 | POST-MVP requirements map to a boundary test proving the first release behaves exactly as `02` describes (feature absent or limited). | Every requirement keeps a test without building post-MVP features. |
| 3 | NFR-SEC-11 (10 requests/minute per IP on `/api/v1/auth/**`, 300/minute per user elsewhere, 429 `RATE_LIMITED` with `Retry-After`) is built in T7.03 together with the other request controls. | One filter next to the headers and body limits; the login lock (FR-AUTH-07) already protects accounts from M1. |
| 4 | `audit_log` and `AuditService` arrive with V10 in T3.06; the stock adjustment and wastage use cases from T2.05 are wired to it in the same task, and every later reversal or adjustment writes an audit entry from the start. V15 (T7.05) only adds the immutability triggers, including the one on `audit_log`. | `04-DATA-MODEL.md` section 16 creates `audit_log` in V10 and `V15__create_immutability_triggers.sql` last. |
| 5 | Idempotent routes are `POST /payments/{id}/reverse` and `POST /orders/{id}/deliver`. | The same paths are used in `02`, `03` and `05`. |
| 6 | `DatabaseCleaner` never truncates identity tables; tests create users with unique usernames. | The bootstrap owner must survive for the whole container lifetime. |
| 7 | Performance tests are excluded from the default build and run at the M7 gate and before releases. | Generating the 5-year dataset takes minutes and would exceed the 15-minute CI budget (NFR-OPS-03). |
| 8 | NFR-TEST-06 loop (a) is split into `MvpScenarioTest` and `ProductionScenarioTest`; their dashboard assertions are added in T6.01. | The dashboard exists only from M6, but the loops must run from M3/M4. |
| 9 | Client-observable NFRs (NFR-USE-01..06) are proven by desktop tests in M9 in addition to the backend tests that already cover their server part; NFR-USE-06 uses the desktop interpretation of `06` (32 x 32 px targets, text scale 200 %, contrast 4.5:1). | `06-UI-SPEC.md` section 11 decision 5. |
| 10 | Manual cash/bank corrections are the `ADJUSTMENT` transaction type (no reference, `FINANCE_MANAGE`, reason, never negative, audited `ACCOUNT_ADJUSTED`); chit organiser fees are `CHIT_ORGANISER_PAYMENT`. Both are covered by the type/direction/reference rule test in T3.05 and by endpoint tests in T5.03 and T5.05. | `05-API-SPEC.md` section 22 decision 3 and `04-DATA-MODEL.md` section 7.7. |
| 11 | Go-live loans and chits are tested through `POST /loans/existing` (`repaid_before_go_live`) and `prior_contributions_count`/`_amount` on `POST /chits`, asserting that no cash transaction is created and that outstanding principal and totals include the pre-go-live figures. | `04-DATA-MODEL.md` sections 7.4, 7.5, 13 and decision 18. |

## 16. Traceability table

Every functional and non-functional requirement of `02-REQUIREMENTS.md` maps to at least one test (or a stated check) and to the task that
adds it. `RequirementTraceabilityTest` (T7.01) enforces the MVP part of this table automatically from the test display names.
POST-MVP rows point to the boundary test that proves the first release behaves as specified (the feature is absent or limited exactly
as `02` says) so the design keeps room for it.

| Requirement | Title | Priority | Test(s) | Task(s) |
|---|---|---|---|---|
| FR-AUTH-01 | Log in with username and password | MVP | `AuthApiTest#login_withValidCredentials_returnsTokenPair`<br>`AuthApiTest#login_withWrongPassword_returns401InvalidCredentials`<br>`AuthApiTest#login_withUnknownUser_returnsSameMessageAsWrongPassword`<br>`AuthApiTest#login_inactiveUser_returns401InvalidCredentials`<br>`LoginViewModelTest#invalidCredentials_showsGenericMessage`<br>`LoginUiTest#login_withValidCredentials_opensLandingScreen` | T1.03, T9.05 |
| FR-AUTH-02 | Access token content and lifetime | MVP | `AccessTokenServiceTest#issue_containsIssuerSubjectPermissionsAnd15MinuteExpiry` | T1.03 |
| FR-AUTH-03 | Unauthenticated and expired requests | MVP | `SecurityErrorApiTest#protectedEndpoint_withoutToken_returns401ProblemJson`<br>`SecurityErrorApiTest#protectedEndpoint_withExpiredToken_returns401TokenExpired`<br>`AuthRepositoryTest#refreshFailure_clearsStoredTokenAndAsksForLogin` | T1.06, T9.05 |
| FR-AUTH-04 | Refresh with rotation | MVP | `AuthApiTest#refresh_withValidToken_rotatesAndOldTokenStopsWorking`<br>`AuthApiTest#refresh_withExpiredToken_returns401TokenExpired`<br>`AuthApiTest#refresh_withUnknownToken_returns401Unauthenticated`<br>`AuthRepositoryTest#expiredAccessToken_refreshesOnceAndRetries`<br>`AuthRepositoryTest#concurrentRequests_shareOneRefresh` | T1.05, T9.05 |
| FR-AUTH-05 | Refresh-token reuse detection | MVP | `AuthApiTest#refresh_withRotatedToken_revokesAllTokensOfUser` | T1.05 |
| FR-AUTH-06 | Log out | MVP | `AuthApiTest#logout_revokesToken_andIsIdempotent` | T1.05 |
| FR-AUTH-07 | Brute-force protection | MVP | `UserTest#recordFailedLogin_fifthFailure_locksAccountFor15Minutes`<br>`UserTest#isLocked_afterLockExpiry_returnsFalse`<br>`AuthApiTest#login_afterFiveFailures_rejectsCorrectPasswordFor15Minutes`<br>`LoginLockoutConcurrencyTest#parallelWrongPasswords_countEveryFailure`<br>`LoginViewModelTest#invalidCredentials_showsGenericMessage` | T1.02, T1.04, T9.05 |
| FR-AUTH-08 | Password storage | MVP | `AuthApiTest#login_responseNeverContainsPasswordHash`<br>`PasswordStorageIntegrationTest#storedHash_usesBcryptPrefix`<br>`SensitiveDataLogTest#loginAndPayment_logsContainNoPasswordTokenOrAccountNumber` | T1.03, T7.04 |
| FR-AUTH-09 | Permission-based authorization | MVP | `PermissionCatalogIntegrationTest#permissionEnum_matchesPermissionsTable`<br>`SecurityErrorApiTest#staffUser_withoutPermission_returns403AccessDenied`<br>`EndpointSecurityCoverageTest#everyEndpoint_rejectsMissingTokenAndMissingPermission`<br>`PreAuthorizePermissionTest#everyPreAuthorize_namesExistingPermission`<br>`NavigationViewModelTest#entriesWithoutPermission_areHidden`<br>`NavigationViewModelTest#staffWithoutReportView_landsOnInventory` | T1.02, T1.06, T1.13, T9.06 |
| FR-AUTH-10 | Bootstrap of the first owner | MVP | `OwnerBootstrapIntegrationTest#emptyUsersTable_withEnvironmentVariables_createsOwner`<br>`OwnerBootstrapIntegrationTest#existingUsers_skipsBootstrap` | T1.07 |
| FR-AUTH-11 | Current user | MVP | `AuthApiTest#me_returnsCallerRolesAndPermissions`<br>`LoginUiTest#login_withValidCredentials_opensLandingScreen` | T1.07, T9.05 |
| FR-AUTH-12 | Authenticated endpoints only | MVP | `OpenApiDevProfileTest#apiDocs_inDevProfile_returnsOurTitle`<br>`OpenApiProdProfileTest#apiDocs_inProdProfile_areNotExposed`<br>`OpenApiProdProfileTest#swaggerUi_inProdProfile_isNotExposed`<br>`SecurityErrorApiTest#publicEndpoints_withoutToken_areReachable` | T0.08, T1.06 |
| FR-AUTH-13 | Audit identity on records | MVP | `AuditingIntegrationTest#createdRecord_storesCreatorFromTokenAndVersion` | T1.09 |
| FR-AUTH-14 | User management | POST-MVP | `AuthApiTest#userManagementEndpoints_areNotExposedInFirstRelease` | T1.07 |
| FR-AUTH-15 | Change own password | POST-MVP | `AuthApiTest#changePasswordEndpoint_isNotExposedInFirstRelease` | T1.07 |
| FR-PARTY-01 | Create a party | MVP | `PartyTest#create_withoutRole_isRejected`<br>`PartyApiTest#create_withValidRequest_returns201WithPartyCode`<br>`PartyApiTest#create_withBlankName_returns400ValidationFailed`<br>`PartyFormViewModelTest#fieldErrors_shownUnderMatchingFields` | T1.08, T1.09, T9.09 |
| FR-PARTY-02 | Roles of a party | MVP | `PartyTest#create_withSeveralRoles_keepsAll` | T1.08 |
| FR-PARTY-03 | Unique, gap-free party codes | MVP | `PartyApiTest#create_withValidRequest_returns201WithPartyCode` | T1.09 |
| FR-PARTY-04 | Phone numbers | MVP | `PartyTest#replacePhones_withTwoPrimaries_isRejected`<br>`PartyTest#replacePhones_withoutPrimary_makesFirstPrimary` | T1.08 |
| FR-PARTY-05 | Optional address | MVP | `PartyApiTest#create_withoutAddress_isAccepted`<br>`PartyApiTest#create_withTooLongCity_returns400ValidationFailed` | T1.09 |
| FR-PARTY-06 | View a party | MVP | `PartyApiTest#get_returnsRolesPhonesDefaultsAndAuditFields`<br>`PartyApiTest#get_unknownId_returns404ResourceNotFound`<br>`PartyDetailViewModelTest#tabsShownPerRole` | T1.09, T9.09 |
| FR-PARTY-07 | List and search parties | MVP | `PartyApiTest#list_filteredByRoleStatusAndSearch_returnsPage`<br>`PartyApiTest#list_searchWithPercentSign_isEscaped`<br>`PartyApiTest#list_unknownSortProperty_returns400ValidationFailed`<br>`PartyListViewModelTest#search_debounced300msAndCancelsStaleRequests` | T1.10, T9.09 |
| FR-PARTY-08 | Update a party | MVP | `PartyApiTest#patch_replacesPhonesAndKeepsPartyCode` | T1.11 |
| FR-PARTY-09 | Add a role to an existing party | MVP | `PartyTest#addRole_toExistingParty_keepsExistingRoles`<br>`PartyApiTest#patch_addRole_keepsExistingRoles` | T1.08, T1.11 |
| FR-PARTY-10 | Deactivate and reactivate | MVP | `PartyTest#deactivate_whenInactive_throwsInvalidStateTransition`<br>`PartyApiTest#deactivate_thenActivate_switchesStatus` | T1.08, T1.11 |
| FR-PARTY-11 | Inactive parties cannot be used in new transactions | MVP | `PartyServiceIntegrationTest#requireActiveWithRole_inactiveParty_throwsPartyInactive` | T1.12 |
| FR-PARTY-12 | Role must match the operation | MVP | `PartyServiceIntegrationTest#requireActiveWithRole_missingRole_throwsInvalidPartyRole`<br>`PurchaseApiTest#create_withNonSupplier_returns422InvalidPartyRole` | T1.12, T2.08 |
| FR-PARTY-13 | Supplier default credit days | MVP | `PartyTest#supplierCreditDays_withoutSupplierRole_isRejected` | T1.08 |
| FR-PARTY-14 | Worker default rates | MVP | `PartyTest#workerRates_withoutWorkerRole_isRejected`<br>`WorkRecordApiTest#record_copiesRateFromWorkerDefaults` | T1.08, T4.09 |
| FR-PARTY-15 | No hard delete | MVP | `PartyApiTest#delete_isNotSupported_returns405` | T1.11 |
| FR-PARTY-16 | Lender role | MVP | `MigrationIntegrationTest#partyRolesCheck_acceptsLender`<br>`PartyTest#create_withSeveralRoles_keepsAll`<br>`PartyServiceIntegrationTest#requireActiveWithRole_lenderRole_isAccepted`<br>`LoanApiTest#create_forNonLender_returns422InvalidPartyRole` | T1.02, T1.08, T1.12, T5.04 |
| FR-PARTY-17 | Customer-specific credit period | POST-MVP | `CustomerOutstandingApiTest#outstanding_showsAgeWithoutCreditPeriodAlerts` | T3.10 |
| FR-PROC-01 | Create a purchase | MVP | `PurchaseApiTest#create_withValidRequest_returns201WithPurchaseNumber`<br>`PurchaseApiTest#create_withNonSupplier_returns422InvalidPartyRole` | T2.08 |
| FR-PROC-02 | Purchase numbers | MVP | `PurchaseApiTest#create_withValidRequest_returns201WithPurchaseNumber`<br>`PurchaseNumberConcurrencyTest#parallelCreates_produceUniqueGapFreeNumbers` | T2.08 |
| FR-PROC-03 | Purchase value is derived | MVP | `PurchaseTest#orderedValue_isRoundedHalfUp`<br>`PurchaseApiTest#create_responseValueComputedByBackend` | T2.07, T2.08 |
| FR-PROC-04 | Due date | MVP | `PurchaseTest#dueDate_isPurchaseDatePlusCreditDays`<br>`PurchaseApiTest#create_cashPurchase_dueDateEqualsPurchaseDate`<br>`PurchaseFormViewModelTest#cashPurchase_forcesCreditDaysToZero` | T2.07, T2.08, T9.10 |
| FR-PROC-05 | Purchase price and weight are historical | MVP | `PurchaseApiTest#patch_rateOrWeight_isRejected` | T2.08 |
| FR-PROC-06 | View a purchase | MVP | `PurchaseApiTest#get_returnsReceiptsReturnsAndDerivedWeights` | T2.08 |
| FR-PROC-07 | List purchases | MVP | `PurchaseApiTest#list_filteredBySupplierStatusAndDateRange` | T2.08 |
| FR-PROC-08 | Record a material receipt | MVP | `PurchaseTest#recordReceipt_beyondOrdered_throwsPurchaseReceiptExceeded`<br>`PurchaseReceiptApiTest#receive_acceptedWeightOnly_entersRawStock`<br>`PurchaseReceiptApiTest#receive_beyondOrdered_returns409PurchaseReceiptExceeded`<br>`ReceiveSilkViewModelTest#receiptExceeded_showsErrorAtReceivedField`<br>`ReceiveSilkUiTest#receiveSilk_keyboardOnlyFlow` | T2.07, T2.09, T9.10 |
| FR-PROC-09 | Receipt weights are consistent | MVP | `PurchaseTest#recordReceipt_acceptedPlusRejectedAboveReceived_isRejected` | T2.07 |
| FR-PROC-10 | Only accepted material enters stock | MVP | `PurchaseReceiptApiTest#receive_acceptedWeightOnly_entersRawStock` | T2.09 |
| FR-PROC-11 | Purchase receiving status | MVP | `PurchaseTest#recordReceipt_statusMovesPartialThenFull`<br>`PurchaseReceiptApiTest#receive_250Ordered100Received_thenPlus80Accepted_thenPlus100Rejected` | T2.07, T2.09 |
| FR-PROC-12 | Cancel a purchase | MVP | `PurchaseTest#cancel_afterReceipt_throwsInvalidStateTransition`<br>`PurchaseApiTest#cancel_withoutReceipts_setsCancelled`<br>`PurchaseDetailViewModelTest#actionsEnabledByStatus` | T2.07, T2.10, T9.10 |
| FR-PROC-13 | Return material to the supplier | MVP | `SupplierReturnApiTest#return_createsPurchaseReturnMovement_receiptUnchanged`<br>`SupplierReturnApiTest#return_moreThanAvailable_returns409InsufficientInventory`<br>`ReturnDialogViewModelTest#insufficientInventory_showsMessage` | T2.10, T9.10 |
| FR-PROC-14 | Receipts and returns are never rewritten | MVP | `SupplierReturnApiTest#return_createsPurchaseReturnMovement_receiptUnchanged`<br>`ImmutabilityTriggerIntegrationTest#updateOrDeleteOfLedgerRow_isRejectedByTrigger` | T2.10, T7.05 |
| FR-PROC-15 | Supplier payable per purchase (ASSUMPTION Q1) | MVP | `SupplierPayableApiTest#payable_isAcceptedMinusReturnedTimesRateMinusAllocations` | T3.09 |
| FR-PROC-16 | Overdue purchases | MVP | `SupplierPayableApiTest#purchase_pastDueWithPayable_isOverdue` | T3.09 |
| FR-PROC-17 | Vuda warp purchases | MVP | `PurchaseReceiptApiTest#receive_vudaWarp_entersRawStockAsVuda` | T2.09 |
| FR-PROC-18 | Purchase history is preserved | MVP | `PurchaseApiTest#delete_isNotSupported` | T2.08 |
| FR-PROC-19 | Retry-safe receipts | MVP | `PurchaseReceiptApiTest#receive_replayWithSameKey_createsOneReceipt`<br>`SupplierReturnApiTest#return_replayWithSameKey_createsOneReturn`<br>`IdempotencyRouteCoverageTest#exactlyTheEighteenListedRoutes_requireIdempotencyKey`<br>`ReceiveSilkUiTest#receiveSilk_keyboardOnlyFlow` | T3.12, T5.06, T9.10 |
| FR-PROC-20 | Low-stock indication when buying | POST-MVP | `InventoryApiTest#summary_exposesRawSilkAvailableForPurchaseScreen` | T2.04 |
| FR-INV-01 | Stock changes only through movements | MVP | `InventoryServiceIntegrationTest#recordMovement_writesMovementAndBalanceInOneTransaction`<br>`InventoryApiTest#postMovement_routeDoesNotExist`<br>`ModuleBoundaryTest#modules_onlyUseOtherModulesApplicationLayer`<br>`MovementHistoryViewModelTest#offersNoCreateAction` | T2.02, T2.04, T2.11, T9.11 |
| FR-INV-02 | Movement record | MVP | `InventoryServiceIntegrationTest#recordMovement_writesMovementAndBalanceInOneTransaction` | T2.02 |
| FR-INV-03 | Valid movement types | MVP | `MovementRulesTest#legalPairs_matchMovementTypeTable`<br>`InventoryServiceIntegrationTest#recordMovement_conversionToWarp_updatesWipUnderTargetProduct` | T2.01, T2.02 |
| FR-INV-04 | Balances are a projection updated atomically | MVP | `InventoryServiceIntegrationTest#recordMovement_writesMovementAndBalanceInOneTransaction` | T2.02 |
| FR-INV-05 | Stock never goes negative | MVP | `InventoryServiceIntegrationTest#recordMovement_moreThanAvailable_throwsInsufficientInventory`<br>`InventoryConcurrencyTest#parallel80And70From100_exactlyOneSucceeds` | T2.02, T2.03 |
| FR-INV-06 | Deadlock-free locking | MVP | `InventoryConcurrencyTest#oppositeTwoBalanceMovements_doNotDeadlock` | T2.03 |
| FR-INV-07 | Stock summary | MVP | `InventoryApiTest#summary_returnsBalancesTotalsAndCumulativeWastage`<br>`StockSummaryViewModelTest#balancesShownAsReturned` | T2.04, T9.11 |
| FR-INV-08 | Movement history | MVP | `InventoryApiTest#movements_filteredByProductLocationAndDate_returnsPage` | T2.04 |
| FR-INV-09 | Balance reconciliation check | MVP | `InventoryReconciliationIntegrationTest#balances_equalSumOfMovements` | T2.06 |
| FR-INV-10 | Vuda warp is trade-only | MVP | `MovementRulesTest#vudaWarp_inConsumptionIssueOrOutput_isRejected`<br>`ProductionBatchApiTest#create_vudaWarp_returns422InvalidProductForOperation` | T2.01, T4.02 |
| FR-INV-11 | Record wastage manually | MVP | `InventoryApiTest#wastage_recordsMovementWithReason` | T2.05 |
| FR-INV-12 | Stock adjustment (Owner only) | MVP | `InventoryApiTest#adjustment_withoutPermission_returns403`<br>`InventoryApiTest#adjustment_belowZero_returns409InsufficientInventory`<br>`AuditLogIntegrationTest#stockAdjustmentAndWastage_writeAuditEntryWithReason`<br>`StockAdjustmentViewModelTest#reasonIsRequired` | T2.05, T3.06, T9.11 |
| FR-INV-13 | Opening stock at go-live | MVP | `InventoryApiTest#openingBalance_recordedOncePerProductAndLocation`<br>`GoLiveImportIntegrationTest#openingDataTotals_matchReconciliationQueries` | T2.05, T8.07 |
| FR-INV-14 | Material outside with manufacturers | MVP | `InventoryApiTest#externalWip_perManufacturerAndJob` | T4.07 |
| FR-INV-15 | Weight precision | MVP | `WeightTest#of_whenOneDecimal_hasScaleThree`<br>`WeightTest#round_whenExactlyHalf_roundsUp`<br>`WeightTest#isPositive_zero_returnsFalse`<br>`MovementRulesTest#quantityWithFourDecimals_isRejected`<br>`WeightFieldTest#fourthDecimal_isRejectedWhileTyping` | T0.06, T2.01, T9.07 |
| FR-INV-16 | Wastage and discrepancy visibility | MVP | `InventoryApiTest#summary_returnsBalancesTotalsAndCumulativeWastage` | T2.04 |
| FR-INV-17 | Customer returns | POST-MVP | `MovementRulesTest#customerReturnPair_isDefinedForLaterUse` | T2.01 |
| FR-INV-18 | Low-stock threshold and lot costing | POST-MVP | `InventoryApiTest#summary_hasNoThresholdOrLotFieldsInFirstRelease` | T2.04 |
| FR-ORD-01 | Create an order | MVP | `OrderApiTest#create_withItems_returns201WithOrderNumberAndAmounts`<br>`OrderApiTest#create_forNonCustomer_returns422InvalidPartyRole`<br>`OrderApiTest#create_expectedDateBeforeOrderDate_returns400` | T3.02 |
| FR-ORD-02 | Item amounts are derived | MVP | `CustomerOrderTest#itemAmount_isRoundedHalfUpToPaise`<br>`OrderApiTest#create_withItems_returns201WithOrderNumberAndAmounts`<br>`OrderFormViewModelTest#amountShownOnlyFromApiResponse` | T3.01, T3.02, T9.12 |
| FR-ORD-03 | Rates are historical | MVP | `OrderApiTest#rate_isStoredOnItemAndNeverRecalculated` | T3.02 |
| FR-ORD-04 | View an order | MVP | `OrderApiTest#get_returnsItemsPaymentStatusAndOutstanding` | T3.02 |
| FR-ORD-05 | List and filter orders | MVP | `OrderApiTest#list_filteredByCustomerStatusAndPending` | T3.02 |
| FR-ORD-06 | Edit an order before it is locked | MVP | `OrderApiTest#patch_placedOrder_recalculatesAmounts`<br>`OrderApiTest#patch_lockedOrder_returns409OrderLocked` | T3.03 |
| FR-ORD-07 | Confirm an order | MVP | `CustomerOrderTest#transitions_followStateMachine`<br>`OrderApiTest#confirm_placedOrder_becomesObligation`<br>`OrderDetailViewModelTest#onlyLegalNextActionsAreShown` | T3.01, T3.03, T9.12 |
| FR-ORD-08 | Order locking when work starts | MVP | `CustomerOrderTest#edit_afterLock_throwsOrderLocked`<br>`OrderApiTest#patch_lockedOrder_returns409OrderLocked`<br>`ProductionBatchApiTest#start_consumesRawSilkAndLocksOrders`<br>`OrderDetailViewModelTest#lockedOrder_formIsReadOnly` | T3.01, T3.03, T4.03, T9.12 |
| FR-ORD-09 | Cancel an order | MVP | `OrderApiTest#cancel_withAllocations_returns409InvalidStateTransition` | T3.03 |
| FR-ORD-10 | Mark an order ready | MVP | `CustomerOrderTest#transitions_followStateMachine`<br>`OrderApiTest#ready_fromConfirmed_whenFilledFromStock` | T3.01, T3.03 |
| FR-ORD-11 | Deliver an order | MVP | `OrderDeliveryApiTest#deliver_warpItems_createDeliveryMovementsFromFinishedStock`<br>`OrderDeliveryApiTest#deliver_rawSilkAndVuda_createDirectSaleFromRawStock`<br>`OrderDeliveryApiTest#deliver_insufficientStock_returns409AndNothingChanges`<br>`OrderDetailViewModelTest#onlyLegalNextActionsAreShown` | T3.04, T9.12 |
| FR-ORD-12 | No partial delivery of an item | MVP | `OrderDeliveryApiTest#deliver_allItemsTogether_noPartialItem` | T3.04 |
| FR-ORD-13 | Complete an order | MVP | `CustomerOrderTest#transitions_followStateMachine`<br>`OrderApiTest#complete_afterDelivery_independentOfPayment` | T3.01, T3.03 |
| FR-ORD-14 | Payment status is derived | MVP | `OrderApiTest#paymentStatus_derivedFromAllocations` | T3.10 |
| FR-ORD-15 | Direct sale of raw silk and vuda warp | MVP | `OrderDeliveryApiTest#deliver_rawSilkAndVuda_createDirectSaleFromRawStock` | T3.04 |
| FR-ORD-16 | Pending and late orders | MVP | `OrderApiTest#list_lateFlag_whenExpectedDatePassed` | T3.02 |
| FR-ORD-17 | Orders are never deleted | MVP | `OrderApiTest#delete_isNotSupported` | T3.02 |
| FR-ORD-18 | Order numbers | MVP | `OrderApiTest#create_withItems_returns201WithOrderNumberAndAmounts` | T3.02 |
| FR-ORD-19 | Per-item delivery, customer returns and final bill | POST-MVP | `OrderDeliveryApiTest#partialItemDelivery_isNotOfferedInFirstRelease` | T3.04 |
| FR-PAY-01 | Record a customer payment | MVP | `PaymentApiTest#customerPayment_allocatesOldestFirstAndRecordsTransaction` | T3.07 |
| FR-PAY-02 | Oldest-first allocation | MVP | `CustomerAllocationTest#oldestFirst_20k30k40kPay45k_settlesAThenB25k`<br>`RecordPaymentUiTest#recordPayment_showsServerAllocations` | T3.07, T9.13 |
| FR-PAY-03 | Explicit allocations are validated | MVP | `PaymentApiTest#explicitAllocation_aboveOutstanding_returns409AllocationExceeded`<br>`PaymentApiTest#explicitAllocation_otherCustomersItem_returns422InvalidAllocation`<br>`RecordPaymentViewModelTest#manualAllocationAboveOutstanding_isBlocked` | T3.08, T9.13 |
| FR-PAY-04 | Customer advance | MVP | `PaymentApiTest#customerPayment_excess_becomesAdvance` | T3.07 |
| FR-PAY-05 | Advance is applied when an order is confirmed (ASSUMPTION Q2) | MVP | `PaymentApiTest#confirmOrder_appliesAvailableAdvanceOldestFirst` | T3.08 |
| FR-PAY-06 | Atomic settlement | MVP | `PaymentApiTest#customerPayment_allocatesOldestFirstAndRecordsTransaction` | T3.07 |
| FR-PAY-07 | Idempotent payment requests | MVP | `IdempotencyIntegrationTest#replay_sameKeySameBody_returnsStoredResponse`<br>`IdempotencyIntegrationTest#sameKeyDifferentBody_returns409DuplicateRequest`<br>`IdempotencyIntegrationTest#requiredRoute_withoutKey_returns400ValidationFailed`<br>`IdempotencyConcurrencyTest#parallelDuplicates_createOnePayment`<br>`OrderDeliveryApiTest#deliver_replayWithSameKey_deliversOnce`<br>`PaymentApiTest#record_replayWithSameKey_returnsStoredResponse`<br>`PaymentReversalApiTest#reverse_replayWithSameKey_returnsStoredResponse`<br>`OutsourcingJobApiTest#receive_replayWithSameKey_receivesOnce`<br>`LoanApiTest#moneyWrites_replayWithSameKey_returnStoredResponse`<br>`ChitApiTest#moneyWrites_replayWithSameKey_returnStoredResponse`<br>`IdempotencyRouteCoverageTest#exactlyTheEighteenListedRoutes_requireIdempotencyKey`<br>`SubmitControllerTest#retryAfterTimeout_reusesIdempotencyKey` | T3.06, T3.12, T4.07, T5.04, T5.05, T5.06, T9.07 |
| FR-PAY-08 | Record a supplier payment | MVP | `SupplierPaymentApiTest#supplierPayment_allocatesOldestPurchaseFirst` | T3.09 |
| FR-PAY-09 | Record a manufacturer payment | MVP | `ManufacturerPaymentApiTest#payment_allocatesOldestJobFirst` | T4.08 |
| FR-PAY-10 | Record a worker payment | MVP | `WorkerPaymentApiTest#payment_allocatesOldestWorkFirst` | T4.10 |
| FR-PAY-11 | Party role must match the direction | MVP | `PaymentApiTest#directionIn_forSupplier_returns422InvalidPartyRole` | T3.07 |
| FR-PAY-12 | Method and account must be consistent | MVP | `PaymentApiTest#cashMethod_withBankAccount_returns422InvalidPaymentMethod`<br>`RecordPaymentViewModelTest#cashMethod_listsOnlyCashAccounts` | T3.07, T9.13 |
| FR-PAY-13 | Money precision | MVP | `MoneyTest#equals_differentScale_isFalseButCompareToIsZero`<br>`MoneyTest#isPositive_zeroAndNegative_returnFalse`<br>`PaymentApiTest#amountWithThreeDecimals_returns400`<br>`MoneyFieldTest#thirdDecimal_isRejectedWhileTyping` | T0.06, T3.07, T9.07 |
| FR-PAY-14 | View and list payments | MVP | `PaymentApiTest#get_returnsAllocationsAndTransactionId` | T3.11 |
| FR-PAY-15 | Reverse a payment | MVP | `PaymentReversalApiTest#reverse_marksReversedAndAddsCompensatingTransaction`<br>`PaymentReversalApiTest#reverse_withoutPermission_returns403`<br>`PaymentReversalConcurrencyTest#parallelReversals_reverseOnce`<br>`PaymentDetailViewModelTest#reverse_requiresReason` | T3.11, T9.13 |
| FR-PAY-16 | Payments are immutable | MVP | `PaymentApiTest#putOrDelete_isNotSupported` | T3.11 |
| FR-PAY-17 | Customer outstanding (ASSUMPTION Q2) | MVP | `CustomerOutstandingApiTest#outstanding_confirmedOrdersMinusAllocations` | T3.10 |
| FR-PAY-18 | Customer outstanding ageing | MVP | `CustomerOutstandingApiTest#outstanding_groupsByAgeBuckets` | T3.10 |
| FR-PAY-19 | Supplier payables | MVP | `SupplierPayableApiTest#payable_isAcceptedMinusReturnedTimesRateMinusAllocations` | T3.09 |
| FR-PAY-20 | Manufacturer payable | MVP | `ManufacturerPaymentApiTest#payable_isReceivedKgTimesHistoricalRate` | T4.08 |
| FR-PAY-21 | Worker payable | MVP | `WorkerPaymentApiTest#payable_forPayrollWeek` | T4.10 |
| FR-PAY-22 | Opening obligations at go-live | MVP | `OpeningObligationApiTest#openingReceivable_isAllocatableOldestFirst`<br>`GoLiveImportIntegrationTest#openingDataTotals_matchReconciliationQueries` | T3.13, T8.07 |
| FR-PAY-23 | Settlement history | MVP | `PaymentApiTest#allocations_listedAfterReversalAsReleased` | T3.11 |
| FR-PAY-24 | Concurrent payments cannot over-allocate | MVP | `PaymentConcurrencyTest#parallelPayments_neverOverAllocate` | T3.12 |
| FR-PAY-25 | Refunds, cheque clearance, supplier advances | POST-MVP | `PaymentApiTest#refundDirection_isNotOfferedInFirstRelease` | T3.07 |
| FR-PROD-01 | Create a production batch | MVP | `ProductionBatchApiTest#create_withOrderLinks_returnsPlannedBatch` | T4.02 |
| FR-PROD-02 | Start a batch (consume raw silk) | MVP | `ProductionBatchApiTest#start_consumesRawSilkAndLocksOrders`<br>`BatchDetailViewModelTest#startRequiresConfirmation` | T4.03, T9.16 |
| FR-PROD-03 | Complete a batch (reconciliation) | MVP | `ProductionBatchTest#complete_inputNotReconciled_throwsProductionNotReconciled`<br>`ProductionBatchApiTest#complete_100In94Out4Waste2Discrepancy_succeeds`<br>`ProductionBatchApiTest#complete_100In94Out10Waste_returns422NotReconciled`<br>`CompleteBatchViewModelTest#saveEnabledOnlyWhenOutputWastageDiscrepancyEqualInput` | T4.01, T4.04, T9.16 |
| FR-PROD-04 | Discrepancy is explicit | MVP | `ProductionBatchTest#complete_inputNotReconciled_throwsProductionNotReconciled`<br>`ProductionBatchApiTest#complete_100In94Out10Waste_returns422NotReconciled` | T4.01, T4.04 |
| FR-PROD-05 | Cancel a batch | MVP | `ProductionBatchTest#cancel_afterStart_throwsInvalidStateTransition`<br>`ProductionBatchApiTest#cancel_planned_setsCancelled` | T4.01, T4.04 |
| FR-PROD-06 | View and list batches | MVP | `ProductionBatchApiTest#get_returnsLinksWeightsAndWorkRecords` | T4.02 |
| FR-PROD-07 | Many-to-many with order items | MVP | `ProductionBatchApiTest#create_withOrderLinks_returnsPlannedBatch` | T4.02 |
| FR-PROD-08 | Atomic start and completion | MVP | `ProductionBatchApiTest#start_insufficientRawSilk_returns409AndNoStatusChange`<br>`ProductionBatchApiTest#complete_100In94Out4Waste2Discrepancy_succeeds` | T4.03, T4.04 |
| FR-PROD-09 | Concurrent starts cannot oversubscribe stock | MVP | `ProductionConcurrencyTest#parallelStarts_cannotOversubscribeRawSilk` | T4.03 |
| FR-PROD-10 | Completed batches are immutable | MVP | `ProductionBatchTest#completed_cannotBeEdited` | T4.01 |
| FR-PROD-11 | Edit a planned batch | MVP | `ProductionBatchApiTest#patch_plannedBatch_changesInputAndLinks` | T4.02 |
| FR-PROD-12 | Production analytics | POST-MVP | `ProductionReportApiTest#report_showsWastagePercentagePerBatch` | T6.05 |
| FR-OUT-01 | Create an outsourcing job | MVP | `OutsourcingJobApiTest#create_withoutOrder_isAccepted`<br>`OutsourcingJobApiTest#create_nonManufacturer_returns422InvalidPartyRole` | T4.06 |
| FR-OUT-02 | Issue raw silk to the manufacturer | MVP | `OutsourcingJobApiTest#issue_movesRawSilkToExternalWipAndLocksLinkedOrder` | T4.06 |
| FR-OUT-03 | Receive finished warp | MVP | `OutsourcingJobTest#receive_beyondIssued_throwsOutsourceReceiptExceeded`<br>`OutsourcingJobApiTest#receive_movesWarpToFinishedStockWithWastage`<br>`ReceiveWarpViewModelTest#receiveAboveStillOutside_showsError` | T4.05, T4.07, T9.16 |
| FR-OUT-04 | Job completion | MVP | `OutsourcingJobTest#receivedPlusWastageEqualsIssued_completesJob` | T4.05 |
| FR-OUT-05 | Manufacturer payable is historical | MVP | `ManufacturerPaymentApiTest#payable_isReceivedKgTimesHistoricalRate` | T4.08 |
| FR-OUT-06 | Cancel a job | MVP | `OutsourcingJobTest#cancel_afterIssue_throwsInvalidStateTransition`<br>`OutsourcingJobApiTest#cancel_created_setsCancelled` | T4.05, T4.07 |
| FR-OUT-07 | View and list jobs | MVP | `OutsourcingJobApiTest#get_returnsIssuedReceivedRemainingAndPayable` | T4.07 |
| FR-OUT-08 | The order link is optional | MVP | `OutsourcingJobApiTest#create_withoutOrder_isAccepted`<br>`OutsourcingJobApiTest#issue_movesRawSilkToExternalWipAndLocksLinkedOrder` | T4.06 |
| FR-OUT-09 | Atomic issue and receipt | MVP | `OutsourcingJobApiTest#issue_movesRawSilkToExternalWipAndLocksLinkedOrder`<br>`OutsourcingJobApiTest#receive_movesWarpToFinishedStockWithWastage`<br>`OutsourcingJobApiTest#receive_replayWithSameKey_receivesOnce` | T4.06, T4.07 |
| FR-OUT-10 | Concurrent issues cannot oversubscribe stock | MVP | `OutsourcingConcurrencyTest#parallelIssues_cannotOversubscribeRawSilk` | T4.06 |
| FR-OUT-11 | Receipts are immutable | MVP | `OutsourcingJobApiTest#receipts_cannotBeEditedOrDeleted`<br>`ImmutabilityTriggerIntegrationTest#updateOrDeleteOfLedgerRow_isRejectedByTrigger` | T4.07, T7.05 |
| FR-OUT-12 | Outsourcing cost analysis | POST-MVP | `OutsourcingReportApiTest#report_perManufacturerAndJob` | T6.05 |
| FR-WORK-01 | Record work | MVP | `WorkRecordApiTest#record_copiesRateFromWorkerDefaults`<br>`WorkRecordApiTest#record_nonWorker_returns422InvalidPartyRole`<br>`RecordWorkUiTest#recordWork_keepsWorkerForNextEntry` | T4.09, T9.16 |
| FR-WORK-02 | Only the relevant measure is allowed | MVP | `WorkRecordTest#rolling_requiresHoursOnly`<br>`WorkRecordTest#warping_requiresQuantityOnly`<br>`RecordWorkViewModelTest#rollingShowsHoursWarpingShowsKg` | T4.09, T9.16 |
| FR-WORK-03 | Historical rate | MVP | `WorkRecordApiTest#record_copiesRateFromWorkerDefaults` | T4.09 |
| FR-WORK-04 | Payment status is derived | MVP | `WorkerPaymentApiTest#status_derivedFromAllocations` | T4.10 |
| FR-WORK-05 | List and filter work records | MVP | `WorkRecordApiTest#list_filteredByWorkerTypeAndDate` | T4.10 |
| FR-WORK-06 | Reverse a mistaken record | MVP | `WorkRecordApiTest#reverse_withAllocations_isRejected` | T4.10 |
| FR-WORK-07 | Records are immutable | MVP | `WorkRecordApiTest#putOrDelete_isNotSupported` | T4.10 |
| FR-WORK-08 | Link to a production batch | MVP | `WorkRecordApiTest#record_linkedToProductionBatch` | T4.09 |
| FR-WORK-09 | Permissions | MVP | `WorkRecordApiTest#record_withoutPermission_returns403` | T4.09 |
| FR-WORK-10 | Worker efficiency analysis | POST-MVP | `WorkerPayableReportApiTest#report_splitsRollingAndWarping` | T6.06 |
| FR-FIN-01 | Create an account | MVP | `FinancialAccountApiTest#create_bankAccount_storesLastFourDigitsOnly`<br>`NewAccountViewModelTest#bankAccount_acceptsOnlyLastFourDigits` | T3.05, T9.14 |
| FR-FIN-02 | Opening balance is a transaction | MVP | `FinancialAccountApiTest#openingBalance_isTransactionNotColumn` | T3.05 |
| FR-FIN-03 | Balances and available money are derived | MVP | `FinancialAccountApiTest#list_balancesDerivedFromTransactions` | T3.05 |
| FR-FIN-04 | Transactions are written only by the system | MVP | `FinancialAccountApiTest#postTransaction_routeDoesNotExist` | T3.05 |
| FR-FIN-05 | Transaction content | MVP | `FinanceServiceIntegrationTest#transaction_requiresExactlyOneReferenceOrNone`<br>`FinanceServiceIntegrationTest#typeDirectionReferenceRules_matchTypeTable` | T3.05 |
| FR-FIN-06 | Money never goes negative | MVP | `FinanceServiceIntegrationTest#recordTransaction_outAboveBalance_throwsInsufficientFunds`<br>`CashConcurrencyTest#parallelCashOuts_neverDriveBalanceNegative`<br>`TransferApiTest#transfer_aboveSourceBalance_returns409InsufficientFunds`<br>`FinancialAccountApiTest#adjustmentDecreaseBelowZero_returns409InsufficientFunds` | T3.05, T5.01, T5.03 |
| FR-FIN-07 | Transaction history | MVP | `FinancialTransactionApiTest#list_filteredByAccountTypeAndDate` | T3.05 |
| FR-FIN-08 | Cash position | MVP | `CashPositionApiTest#cashPosition_cashBanksAndTotal` | T5.03 |
| FR-FIN-09 | Transfer between own accounts | MVP | `TransferApiTest#transfer_createsOutAndInTransactionsNotRevenue`<br>`TransferApiTest#transfer_sameAccount_returns400`<br>`TransferApiTest#transfer_aboveSourceBalance_returns409InsufficientFunds`<br>`TransferViewModelTest#sameAccount_isBlocked` | T5.01, T9.14 |
| FR-FIN-10 | Transfers cannot deadlock | MVP | `TransferConcurrencyTest#oppositeTransfers_doNotDeadlock` | T5.01 |
| FR-FIN-11 | Record an expense | MVP | `ExpenseApiTest#record_createsOutTransaction`<br>`RecordExpenseViewModelTest#defaultPath_needsAtMostFiveInputs` | T5.02, T9.15 |
| FR-FIN-12 | Personal drawings stay separate | MVP | `ExpenseApiTest#personalDrawing_excludedFromBusinessTotals`<br>`ExpensesViewModelTest#personalDrawingsShownSeparately` | T5.02, T9.15 |
| FR-FIN-13 | Expense list and summary | MVP | `ExpenseApiTest#list_andSummaryByCategoryAndKind` | T5.02 |
| FR-FIN-14 | Reverse an expense | MVP | `ExpenseApiTest#reverse_addsCompensatingInTransaction` | T5.02 |
| FR-FIN-15 | Balance adjustment with reason | MVP | `FinanceServiceIntegrationTest#typeDirectionReferenceRules_matchTypeTable`<br>`FinancialAccountApiTest#adjustment_requiresReasonAndPermission`<br>`FinancialAccountApiTest#adjustment_writesAdjustmentTransactionAndAccountAdjustedAudit`<br>`FinancialAccountApiTest#adjustmentDecreaseBelowZero_returns409InsufficientFunds` | T3.05, T5.03 |
| FR-FIN-16 | Close an account | MVP | `FinancialAccountApiTest#close_withNonZeroBalance_isRejected`<br>`AccountsViewModelTest#closeEnabledOnlyAtZeroBalance` | T5.03, T9.14 |
| FR-FIN-17 | Reversal compensates, never deletes | MVP | `PaymentReversalApiTest#reverse_marksReversedAndAddsCompensatingTransaction`<br>`ExpenseApiTest#reverse_addsCompensatingInTransaction` | T3.11, T5.02 |
| FR-FIN-18 | Retry-safe expenses and transfers | MVP | `TransferApiTest#transfer_replayWithSameKey_createsOneTransfer`<br>`ExpenseApiTest#record_replayWithSameKey_createsOneExpense`<br>`ExpenseApiTest#reverse_replayWithSameKey_returnsStoredResponse`<br>`FinancialAccountApiTest#adjustment_replayWithSameKey_returnsStoredResponse`<br>`LoanApiTest#moneyWrites_replayWithSameKey_returnStoredResponse`<br>`IdempotencyRouteCoverageTest#exactlyTheEighteenListedRoutes_requireIdempotencyKey` | T5.01, T5.02, T5.03, T5.04, T5.06 |
| FR-FIN-19 | Configurable expense categories | POST-MVP | `ExpenseApiTest#unknownCategory_returns400MalformedRequest` | T5.02 |
| FR-FIN-20 | Bank integration and mismatch alerts | POST-MVP | `FinancialTransactionApiTest#manualEntryOnly_noStatementImportRoute` | T5.03 |
| FR-LOAN-01 | Register a new loan | MVP | `LoanApiTest#create_recordsLoanReceivedInTransaction`<br>`LoanApiTest#create_storesInterestNotes`<br>`LoanApiTest#create_forNonLender_returns422InvalidPartyRole` | T5.04 |
| FR-LOAN-02 | Register an existing loan at go-live | MVP | `LoanApiTest#registerExisting_storesRepaidBeforeGoLiveWithoutTransaction`<br>`LoanApiTest#registerExisting_outstandingAbovePrincipal_returns400` | T5.04 |
| FR-LOAN-03 | Repay principal | MVP | `LoanApiTest#principalRepayment_aboveOutstanding_isRejected`<br>`LoanDetailViewModelTest#repaymentAboveOutstanding_isBlocked` | T5.04, T9.15 |
| FR-LOAN-04 | Pay interest | MVP | `LoanApiTest#interestPayment_isExpenseNotPrincipal` | T5.04 |
| FR-LOAN-05 | Outstanding principal is derived | MVP | `LoanApiTest#outstanding_subtractsRepaidBeforeGoLiveAndRepayments` | T5.04 |
| FR-LOAN-06 | Close a loan | MVP | `LoanApiTest#close_withOutstanding_isRejected` | T5.04 |
| FR-LOAN-07 | Correct a mistaken loan payment | MVP | `LoanApiTest#reversePayment_addsCompensatingTransactionAndAuditEntry` | T5.04 |
| FR-LOAN-08 | Loan accounting classification | MVP | `LoanApiTest#interestPayment_isExpenseNotPrincipal` | T5.04 |
| FR-LOAN-09 | Loans are never deleted | MVP | `LoanApiTest#putOrDelete_isNotSupported` | T5.04 |
| FR-LOAN-10 | Interest calculation, reminders and repayment advice | POST-MVP | `LoanApiTest#interestIsEnteredManually` | T5.04 |
| FR-CHIT-01 | Create a chit | MVP | `ChitApiTest#create_returnsActiveChit` | T5.05 |
| FR-CHIT-02 | Record a contribution (cash only) | MVP | `ChitApiTest#contribution_nonCash_returns422InvalidPaymentMethod`<br>`ChitDetailViewModelTest#accountSelector_listsCashAccountsOnly` | T5.05, T9.15 |
| FR-CHIT-03 | Record the payout (cash only) | MVP | `ChitApiTest#payout_cashOnly_recordsInTransaction` | T5.05 |
| FR-CHIT-04 | Organiser payment | MVP | `FinanceServiceIntegrationTest#typeDirectionReferenceRules_matchTypeTable`<br>`ChitApiTest#organiserPayment_recordedSeparately`<br>`ChitApiTest#organiserPayment_createsChitOrganiserPaymentOutTransaction` | T3.05, T5.05 |
| FR-CHIT-05 | Chit summary is derived | MVP | `ChitApiTest#summary_derivedFromEntries` | T5.05 |
| FR-CHIT-06 | Close a chit | MVP | `ChitApiTest#close_fromActiveOrMatured` | T5.05 |
| FR-CHIT-07 | Classification in reports | MVP | `LoansAndChitsReportApiTest#chitMovements_areInvestmentNotExpense` | T6.06 |
| FR-CHIT-08 | Existing chit at go-live | MVP | `ChitApiTest#create_withPriorContributions_countsThemWithoutCashTransaction`<br>`ChitApiTest#priorContributionsCountAboveDuration_returns400` | T5.05 |
| FR-CHIT-09 | Correct a mistaken chit entry | MVP | `ChitApiTest#reverseEntry_addsCompensatingTransactionAndAuditEntry`<br>`ChitApiTest#reversePayout_returnsChitToActive`<br>`ChitTest#payoutReversal_movesMaturedBackToActiveOnly` | T5.05 |
| FR-CHIT-10 | Chit calculation and reminders | POST-MVP | `ChitApiTest#amountsAreEnteredWithoutBiddingCalculation` | T5.05 |
| FR-RPT-01 | Owner dashboard | MVP | `DashboardApiTest#summary_returnsCashReceivablesPayablesStockAndDues`<br>`DashboardViewModelTest#showsApiFiguresWithoutComputing` | T6.01, T9.08 |
| FR-RPT-02 | Dashboard separates available money from obligations | MVP | `DashboardApiTest#obligations_neverAddedToAvailableMoney`<br>`DashboardViewModelTest#showsApiFiguresWithoutComputing` | T6.01, T9.08 |
| FR-RPT-03 | Customer outstanding report | MVP | `CustomerOutstandingReportApiTest#report_sortedByOutstanding` | T6.02 |
| FR-RPT-04 | Customer statement | MVP | `CustomerStatementApiTest#statement_openingDebitsCreditsClosing` | T6.02 |
| FR-RPT-05 | Supplier payables report | MVP | `SupplierPayablesReportApiTest#report_withAgeingBuckets` | T6.03 |
| FR-RPT-06 | Purchase report | MVP | `PurchaseReportApiTest#report_groupedBySupplierAndProduct` | T6.03 |
| FR-RPT-07 | Sales and orders report | MVP | `SalesReportApiTest#report_revenueRecognisedAtDelivery` | T6.04 |
| FR-RPT-08 | Expense report | MVP | `ExpenseReportApiTest#report_byCategoryKindAndMonthWithInterest` | T6.04 |
| FR-RPT-09 | Inventory movement report | MVP | `InventoryMovementReportApiTest#report_openingMovementsClosing` | T6.05 |
| FR-RPT-10 | Production and wastage report | MVP | `ProductionReportApiTest#report_showsWastagePercentagePerBatch` | T6.05 |
| FR-RPT-11 | Outsourcing report | MVP | `OutsourcingReportApiTest#report_perManufacturerAndJob` | T6.05 |
| FR-RPT-12 | Cash and bank report | MVP | `CashBankReportApiTest#report_openingInOutClosingPerAccount` | T6.06 |
| FR-RPT-13 | Worker payable and payroll report | MVP | `WorkerPayableReportApiTest#report_splitsRollingAndWarping` | T6.06 |
| FR-RPT-14 | Loans and chits report | MVP | `LoansAndChitsReportApiTest#report_loansAndChits` | T6.06 |
| FR-RPT-15 | Report consistency | MVP | `ReportConsistencyIntegrationTest#reportTotals_equalDetailEndpointTotals` | T6.07 |
| FR-RPT-16 | CSV export for audit | MVP | `CsvExportApiTest#export_returnsCsvWithHeaders`<br>`ReportViewerViewModelTest#exportCsv_usesCurrentFilters` | T6.07, T9.17 |
| FR-RPT-17 | Profit and loss and profitability | POST-MVP | `SalesReportApiTest#report_hasNoProfitFiguresInFirstRelease` | T6.04 |
| FR-NTF-01 | Notification configuration | MVP | `NotificationConfigurationApiTest#put_updatesDaysBeforeDue`<br>`SupplierDueReminderJobIntegrationTest#overdueReminder_repeatsEveryOverdueRepeatDays` | T6.08 |
| FR-NTF-02 | Supplier due reminders | MVP | `SupplierDueReminderJobIntegrationTest#job_createsDueSoonDueTodayAndOverdueReminderKinds`<br>`SupplierDueReminderJobIntegrationTest#jobRunTwiceSameDay_createsNoDuplicateByDedupeKey`<br>`SupplierDueReminderJobIntegrationTest#overdueReminder_repeatsEveryOverdueRepeatDays` | T6.08 |
| FR-NTF-03 | Notification lifecycle | MVP | `NotificationLifecycleTest#paidPurchase_resolvesPendingNotification` | T6.08 |
| FR-NTF-04 | Sending is a side effect after commit | MVP | `NotificationDispatchIntegrationTest#providerFailure_doesNotRollBackBusinessTransaction` | T6.09 |
| FR-NTF-05 | View notifications in the app | MVP | `NotificationApiTest#list_filteredByStatusAndType`<br>`NotificationBellViewModelTest#countsPendingNotifications` | T6.09, T9.17 |
| FR-NTF-06 | Upcoming dues on the dashboard | MVP | `DashboardApiTest#summary_returnsCashReceivablesPayablesStockAndDues` | T6.01 |
| FR-NTF-07 | Other alerts | POST-MVP | `NotificationDispatchIntegrationTest#onlySupplierDueTypeIsScheduledInFirstRelease` | T6.09 |
| FR-NTF-08 | WhatsApp delivery | POST-MVP | `NotificationDispatchIntegrationTest#loggingProviderIsTheOnlyProvider` | T6.09 |
| FR-DOC-01 | Attach a document | MVP | `DocumentApiTest#upload_storesUnderGeneratedId`<br>`AttachDocumentViewModelTest#fileOver10Mb_isRejectedBeforeUpload` | T6.10, T9.17 |
| FR-DOC-02 | Safe storage | MVP | `DocumentApiTest#upload_storesUnderGeneratedId`<br>`DocumentApiTest#upload_pathTraversalName_isSanitised` | T6.10 |
| FR-DOC-03 | List and download | MVP | `DocumentApiTest#list_andDownloadContent` | T6.10 |
| FR-DOC-04 | Documents are immutable | MVP | `DocumentApiTest#overwriteOrDelete_isNotSupported`<br>`DocumentApiTest#upload_supersedingDocument_keepsBothListed`<br>`DocumentApiTest#supersedesUnknownDocument_returns404` | T6.10 |
| FR-DOC-05 | Attaching does not change data | MVP | `DocumentApiTest#upload_doesNotChangeReferencedRecord` | T6.10 |
| FR-DOC-06 | Sales bills, WhatsApp sharing, OCR and GST | POST-MVP | `DocumentApiTest#salesBillGeneration_isNotOfferedInFirstRelease` | T6.10 |
| NFR-PERF-01 | Realistic test dataset | MVP | `PerformanceSmokeTest#referenceDataset_generatedWithConsistentBalances` | T7.08 |
| NFR-PERF-02 | Read latency | MVP | `PerformanceSmokeTest#reads_withinP95Of300ms` | T7.08 |
| NFR-PERF-03 | Dashboard latency | MVP | `PerformanceSmokeTest#dashboard_withinP95Of500ms` | T7.08 |
| NFR-PERF-04 | Write latency | MVP | `PerformanceSmokeTest#writes_withinP95Of500ms` | T7.08 |
| NFR-PERF-05 | Concurrency | MVP | `PerformanceSmokeTest#twentyClientsMixedLoad_noUnexpectedFailures` | T7.08 |
| NFR-PERF-06 | Reports and exports | MVP | `PerformanceSmokeTest#yearReportAndCsvExport_withinBudgets` | T7.08 |
| NFR-PERF-07 | No per-row queries | MVP | `PartyListQueryCountIntegrationTest#list_statementCount_doesNotGrowWithPageSize` | T1.10 |
| NFR-PERF-08 | Start-up time | MVP | `StartupTimeTest#emptyDatabase_migratesAndStartsWithin60Seconds` | T7.08 |
| NFR-SEC-01 | Transport security | MVP | check: `curl -sI http://<host>/actuator/health shows 308 to https and https response carries Strict-Transport-Security`<br>`ServerSetupViewModelTest#httpAddress_onlyAllowedForLocalhost` | T8.02, T9.04 |
| NFR-SEC-02 | Secrets | MVP | `SecurityPropertiesTest#shortJwtSecret_failsStartup`<br>`ConfigurationFailFastTest#missingRequiredVariable_inProd_failsStartup` | T1.01, T8.03 |
| NFR-SEC-03 | Input limits | MVP | `PartyApiTest#create_withTooLongCity_returns400ValidationFailed`<br>`PartyApiTest#list_pageSizeAbove100_isCapped`<br>`DocumentApiTest#upload_over10Mb_isRejected`<br>`RequestLimitApiTest#jsonBodyOver1Mb_isRejected`<br>`AttachDocumentViewModelTest#fileOver10Mb_isRejectedBeforeUpload` | T1.09, T1.10, T6.10, T7.03, T9.17 |
| NFR-SEC-04 | Response headers | MVP | `SecurityHeadersApiTest#responses_carryNosniffFrameDenyNoStore` | T7.03 |
| NFR-SEC-05 | Cross-origin access | MVP | `SecurityHeadersApiTest#corsPreflight_isRejected` | T7.03 |
| NFR-SEC-06 | Sensitive data in logs and responses | MVP | `FinancialAccountApiTest#create_bankAccount_storesLastFourDigitsOnly`<br>`SensitiveDataLogTest#loginAndPayment_logsContainNoPasswordTokenOrAccountNumber`<br>`CredentialStoreTest#onlyRefreshTokenIsStored`<br>`NewAccountViewModelTest#bankAccount_acceptsOnlyLastFourDigits` | T3.05, T7.04, T9.05, T9.14 |
| NFR-SEC-07 | Dependency vulnerabilities | MVP | check: `./gradlew dependencyCheckAnalyze` | T7.02 |
| NFR-SEC-08 | Database privileges | MVP | `DatabaseRoleIntegrationTest#appRole_cannotUpdateOrDeleteLedgerRows` | T7.04 |
| NFR-SEC-09 | Authorization is tested everywhere | MVP | `EndpointSecurityCoverageTest#everyEndpoint_rejectsMissingTokenAndMissingPermission` | T1.13 |
| NFR-SEC-10 | Injection safety | MVP | `PartyApiTest#list_searchWithPercentSign_isEscaped`<br>`SqlSafetyTest#noNativeQueryConcatenatesInput` | T1.10, T7.04 |
| NFR-SEC-11 | Request rate limits | MVP | `ErrorCodeTest#rateLimited_mapsTo429`<br>`RateLimitApiTest#perUser_over300RequestsPerMinute_returns429RateLimited`<br>`RateLimitApiTest#authEndpoints_over10RequestsPerMinutePerIp_return429WithRetryAfter`<br>`RateLimitApiTest#normalUseBelowLimit_isNeverRejected` | T1.01, T7.03 |
| NFR-REL-01 | Invariants hold after any sequence | MVP | `InventoryServiceIntegrationTest#recordMovement_moreThanAvailable_throwsInsufficientInventory`<br>`InventoryApiTest#adjustment_belowZero_returns409InsufficientInventory`<br>`InventoryReconciliationIntegrationTest#balances_equalSumOfMovements`<br>`InvariantPropertyTest#randomOperationSequences_keepAllInvariants` | T2.02, T2.05, T2.06, T5.06 |
| NFR-REL-02 | One use case, one transaction | MVP | `BusinessNumberGeneratorTest#next_outsideTransaction_isRejected`<br>`InventoryServiceIntegrationTest#recordMovement_outsideTransaction_isRejected`<br>`OrderDeliveryApiTest#deliver_insufficientStock_returns409AndNothingChanges` | T0.07, T2.02, T3.04 |
| NFR-REL-03 | Availability | MVP | check: `uptime probe report for the first month shows >= 99.0% availability` | T8.06 |
| NFR-REL-04 | Backups and restore | MVP | check: `deploy/scripts/backup.sh --label drill && deploy/scripts/restore-drill.sh exits 0 and reconciliation totals match` | T8.05 |
| NFR-REL-05 | Dependency failure isolation | MVP | `NotificationDispatchIntegrationTest#providerFailure_doesNotRollBackBusinessTransaction`<br>`FailureDrillIntegrationTest#databaseRestartMidRequest_failsCleanlyAndRetrySucceeds` | T6.09, T7.09 |
| NFR-REL-06 | Time handling | MVP | `ClockUsageTest#businessCodeNeverCallsNowWithoutClock` | T7.07 |
| NFR-REL-07 | Migration safety | MVP | `MigrationIntegrationTest#allMigrations_onEmptyDatabase_validateAgainstEntities`<br>`MigrationIntegrationTest#migrationsApplyOnDataFromPreviousRelease` | T1.02, T7.07 |
| NFR-OBS-01 | Structured request logs | MVP | `StructuredLogTest#requestLogLine_hasIdMethodPathStatusDurationUser` | T7.06 |
| NFR-OBS-02 | Request id | MVP | `RequestIdFilterApiTest#requestId_isEchoedAndGeneratedWhenMissing` | T7.06 |
| NFR-OBS-03 | Health | MVP | `HealthApiTest#health_isUpOnlyWhenDatabaseReachable`<br>`HealthApiTest#prodProfile_hidesHealthDetails` | T7.06 |
| NFR-OBS-04 | Metrics | MVP | `MetricsApiTest#prometheusEndpoint_servedOnManagementPortOnly` | T7.06 |
| NFR-OBS-05 | Operational alerts | MVP | check: `stop the app container: alert fires within 3 minutes; host-check restarts it` | T8.06 |
| NFR-OBS-06 | Audit trail | MVP | `AuditingIntegrationTest#createdRecord_storesCreatorFromTokenAndVersion`<br>`AuditLogIntegrationTest#auditService_writesEntryInCallersTransaction`<br>`AuditLogIntegrationTest#stockAdjustmentAndWastage_writeAuditEntryWithReason`<br>`PaymentReversalApiTest#reverse_writesAuditEntryWithReason`<br>`FinancialAccountApiTest#adjustment_writesAdjustmentTransactionAndAccountAdjustedAudit`<br>`LoanApiTest#reversePayment_addsCompensatingTransactionAndAuditEntry`<br>`ChitApiTest#reverseEntry_addsCompensatingTransactionAndAuditEntry`<br>`ImmutabilityTriggerIntegrationTest#updateOrDeleteOfLedgerRow_isRejectedByTrigger` | T1.09, T3.06, T3.11, T5.03, T5.04, T5.05, T7.05 |
| NFR-USE-01 | Fast daily entries | MVP | `PaymentApiTest#record_withoutDate_defaultsToToday`<br>`ShellUiTest#globalShortcuts_openRecordForms`<br>`RecordPaymentViewModelTest#defaultPath_needsAtMostFiveInputs`<br>`RecordExpenseViewModelTest#defaultPath_needsAtMostFiveInputs`<br>`RecordWorkViewModelTest#rollingShowsHoursWarpingShowsKg` | T3.07, T9.06, T9.13, T9.15, T9.16 |
| NFR-USE-02 | The client never calculates business values | MVP | `OrderApiTest#get_returnsItemsPaymentStatusAndOutstanding`<br>`BigDecimalSerializerTest#roundTrip_12500000_50_staysExact`<br>`DashboardViewModelTest#showsApiFiguresWithoutComputing`<br>`StockSummaryViewModelTest#balancesShownAsReturned`<br>`OrderFormViewModelTest#amountShownOnlyFromApiResponse`<br>`RecordPaymentUiTest#recordPayment_showsServerAllocations` | T3.02, T9.02, T9.08, T9.11, T9.12, T9.13 |
| NFR-USE-03 | Readable numbers | MVP | `IndianFormatTest#money_usesRupeeSignAndIndianGrouping`<br>`IndianFormatTest#weight_showsThreeDecimalsAndKg`<br>`DashboardUiTest#keyFigures_atLeast20sp` | T9.02, T9.08 |
| NFR-USE-04 | Error messages | MVP | `GlobalExceptionHandlerTest#handleBusiness_buildsProblemDetailWithCodeStatusAndTimestamp`<br>`GlobalExceptionHandlerTest#handleGeneric_doesNotLeakExceptionMessage`<br>`GlobalExceptionHandlerApiTest#unknownPath_returns404ProblemWithCode`<br>`GlobalExceptionHandlerApiTest#wrongMethod_returns405MethodNotAllowed`<br>`GlobalExceptionHandlerApiTest#malformedJson_returns400MalformedRequest`<br>`ErrorMessageContractTest#everyProblemDetail_isShortPlainTextWithoutInternalNames`<br>`ApiClientTest#problemDetail_isMappedToApiErrorWithFieldErrors`<br>`ErrorMessagesTest#everyServerErrorCode_hasUserMessage`<br>`PartyFormViewModelTest#fieldErrors_shownUnderMatchingFields`<br>`OfflineStateViewModelTest#connectFailure_keepsLastDataAndShowsRetry` | T0.05, T1.01, T7.09, T9.03, T9.09, T9.18 |
| NFR-USE-05 | Retry-safe submissions | MVP | `IdempotencyConcurrencyTest#parallelDuplicates_createOnePayment`<br>`PaymentApiTest#record_replayWithSameKey_returnsStoredResponse`<br>`ApiClientTest#requestTimeout_is30Seconds`<br>`SubmitControllerTest#retryAfterTimeout_reusesIdempotencyKey`<br>`OfflineStateViewModelTest#connectFailure_keepsLastDataAndShowsRetry` | T3.06, T3.12, T9.03, T9.07, T9.18 |
| NFR-USE-06 | Accessibility | MVP | `StatusChipTest#statusShownAsTextNotColourOnly`<br>`AccessibilityUiTest#textScale200_noLabelIsClipped`<br>`AccessibilityUiTest#iconOnlyButtons_haveAccessibleNames`<br>`AccessibilityUiTest#themeColours_meetContrast4_5To1` | T9.07 |
| NFR-DATA-01 | Exact types | MVP | `MoneyTest#round_whenExactlyHalf_roundsUp`<br>`MoneyTest#round_whenBelowHalf_roundsDown`<br>`MoneyTest#of_whenWholeNumber_hasScaleTwo`<br>`MoneyTest#add_decimalValues_isExactUnlikeDouble`<br>`ExactTypesTest#noDoubleOrFloatInDomainOrApplication`<br>`BigDecimalSerializerTest#roundTrip_12500000_50_staysExact` | T0.06, T7.07, T9.02 |
| NFR-DATA-02 | No stored derived values | MVP | `DerivedValuesTest#noDerivedColumnsInSchema` | T7.07 |
| NFR-DATA-03 | Retention | MVP | `RetentionTest#noScheduledPurgeAndNoDeleteEndpoints` | T7.07 |
| NFR-DATA-04 | Go-live reconciliation | MVP | `GoLiveImportIntegrationTest#openingDataTotals_matchReconciliationQueries` | T8.07 |
| NFR-DATA-05 | Referential integrity | MVP | `ForeignKeyIndexIntegrationTest#everyForeignKeyColumn_hasIndex` | T7.07 |
| NFR-DATA-06 | Unique business numbers | MVP | `BusinessNumberGeneratorTest#next_firstCall_returnsNumberOneWithPrefixAndYear`<br>`BusinessNumberGeneratorTest#next_calledRepeatedly_incrementsByOne`<br>`BusinessNumberGeneratorTest#next_differentPrefixes_haveIndependentCounters`<br>`BusinessNumberGeneratorTest#next_whenTransactionRollsBack_numberIsNotLost`<br>`BusinessNumberGeneratorTest#next_withManyParallelRequests_neverReturnsDuplicates`<br>`PurchaseNumberConcurrencyTest#parallelCreates_produceUniqueGapFreeNumbers` | T0.07, T2.08 |
| NFR-DATA-07 | Audit retrieval and export | MVP | `CsvExportApiTest#export_returnsCsvWithHeaders` | T6.07 |
| NFR-OPS-01 | Deployable artifact | MVP | check: `docker build -t nexora:local . && docker run --rm --entrypoint id nexora:local -u`<br>check: `docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod.example config` | T8.01, T8.02 |
| NFR-OPS-02 | Configuration | MVP | `ConfigurationFailFastTest#missingRequiredVariable_inProd_failsStartup` | T8.03 |
| NFR-OPS-03 | Continuous integration | MVP | check: `CI on a pull request: all jobs green within 15 minutes` | T8.04 |
| NFR-OPS-04 | Rollback | MVP | check: `deploy/scripts/deploy.sh <previous-tag> on staging restores the previous version within 15 minutes` | T8.04 |
| NFR-OPS-05 | Backup before change | MVP | check: `deploy/scripts/backup.sh --label drill && deploy/scripts/restore-drill.sh exits 0 and reconciliation totals match` | T8.05 |
| NFR-OPS-06 | Runbook | MVP | `RunbookCompletenessTest#runbook_hasEveryRequiredProcedure` | T8.08 |
| NFR-OPS-07 | Release and parallel run | MVP | check: ``git tag -l v1.0.0` prints the tag and the weekly reconciliation sheet is signed for every parallel-run week` | T8.09 |
| NFR-TEST-01 | Real database | MVP | `NexoraApplicationTests#contextLoads`<br>`NoInMemoryDatabaseTest#testClasspath_hasNoH2OrHsqldb` | T0.09, T7.01 |
| NFR-TEST-02 | Coverage | MVP | check: `./gradlew jacocoTestCoverageVerification` | T7.01 |
| NFR-TEST-03 | Requirement traceability | MVP | `RequirementTraceabilityTest#everyMvpRequirementId_appearsInATestDisplayName` | T7.01 |
| NFR-TEST-04 | Rejections are tested | MVP | `AuthApiTest#login_withWrongPassword_returns401InvalidCredentials`<br>`PartyApiTest#create_withBlankName_returns400ValidationFailed`<br>`PurchaseReceiptApiTest#receive_beyondOrdered_returns409PurchaseReceiptExceeded`<br>`ErrorCodeCoverageTest#everyErrorCode_isAssertedByAtLeastOneTest` | T1.03, T1.09, T2.09, T7.01 |
| NFR-TEST-05 | Concurrency tests | MVP | `BusinessNumberGeneratorTest#next_withManyParallelRequests_neverReturnsDuplicates`<br>`InventoryConcurrencyTest#parallel80And70From100_exactlyOneSucceeds`<br>`PurchaseNumberConcurrencyTest#parallelCreates_produceUniqueGapFreeNumbers`<br>`CashConcurrencyTest#parallelCashOuts_neverDriveBalanceNegative`<br>`PaymentReversalConcurrencyTest#parallelReversals_reverseOnce`<br>`PaymentConcurrencyTest#parallelPayments_neverOverAllocate`<br>`ProductionConcurrencyTest#parallelStarts_cannotOversubscribeRawSilk`<br>`OutsourcingConcurrencyTest#parallelIssues_cannotOversubscribeRawSilk`<br>`TransferConcurrencyTest#oppositeTransfers_doNotDeadlock` | T0.07, T2.03, T2.08, T3.05, T3.11, T3.12, T4.03, T4.06, T5.01 |
| NFR-TEST-06 | End-to-end scenarios | MVP | `MvpScenarioTest#loginSupplierPurchaseReceiveOrderDeliverPayOutstanding`<br>`ProductionScenarioTest#purchaseProduceDeliverPay`<br>`OutsourcingScenarioTest#issueReceiveDeliverPayManufacturer`<br>`FinanceScenarioTest#transferExpenseLoanChitReversalCashPosition` | T3.14, T4.11, T5.06 |
| NFR-TEST-07 | Migrations in the build | MVP | `MigrationIntegrationTest#allMigrations_onEmptyDatabase_validateAgainstEntities` | T1.02 |
| NFR-TEST-08 | Domain unit tests | MVP | `UserTest#recordFailedLogin_fifthFailure_locksAccountFor15Minutes`<br>`PartyTest#deactivate_whenInactive_throwsInvalidStateTransition`<br>`PurchaseTest#recordReceipt_statusMovesPartialThenFull`<br>`PurchaseTest#cancel_afterReceipt_throwsInvalidStateTransition`<br>`CustomerOrderTest#transitions_followStateMachine`<br>`CustomerOrderTest#illegalTransition_throwsInvalidStateTransition`<br>`ProductionBatchTest#cancel_afterStart_throwsInvalidStateTransition`<br>`OutsourcingJobTest#receivedPlusWastageEqualsIssued_completesJob`<br>`ChitTest#payoutReversal_movesMaturedBackToActiveOnly` | T1.02, T1.08, T2.07, T3.01, T4.01, T4.05, T5.05 |
| NFR-TEST-09 | Independent tests | MVP | check: `./gradlew test -PrandomOrder` | T7.01 |

