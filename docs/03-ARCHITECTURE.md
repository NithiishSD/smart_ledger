# 03 - Architecture

Status: authoritative for how the backend is built. Precedence: `DOMAIN_RULES.md` and `DECISIONS.md` (ADR-001..ADR-034)
win over this file; `CONVENTIONS.md` defines code patterns and the Definition of Done. This file does not repeat them: it
pins the stack, fixes the module map and the cross-module contracts, and records every decision that goes beyond the ADRs
(ADR-025 to ADR-034, recorded in `DECISIONS.md`; see section 16).

## 1. Shape of the system

```
 Desktop client (Kotlin/Compose Desktop, Windows + Linux)            Swagger UI (dev profile only)
            │  HTTPS, JSON, Bearer JWT                           │
            ▼                                                    ▼
      Caddy (TLS, HSTS, access log)  ─────────────►  Spring Boot application  (one JVM, one deployable)
                                                      ├─ Security filter chain (JWT, 401/403 as problem+json)
                                                      ├─ Controllers (api)        HTTP + DTO validation only
                                                      ├─ Use-case services (application)  @Transactional, locks
                                                      ├─ Entities + domain services (domain)  invariants
                                                      └─ Repositories (infrastructure)
                                                                 │ JDBC (HikariCP)
                                                                 ▼
                                                      PostgreSQL 17  (Flyway-owned schema, constraints = last defence)
```

One application, one database, one tenant (ADR-001). No message broker, cache, or second service exists or is planned
(ADR "decisions deliberately NOT made yet"). Side effects (notifications, PDFs) run after commit (ADR-019).

## 2. Pinned stack

Resolved with `./gradlew dependencies --configuration runtimeClasspath` on 2026-10-07 and read from
`Nexora-backend/build.gradle`. Spring Boot manages (BOM) every version except the three marked *explicit*.

| Component | Version | Source of the pin |
|---|---|---|
| Java (toolchain) | 21 (build JVM: OpenJDK 21.0.12) | `java.toolchain.languageVersion = 21` |
| Gradle (wrapper) | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Spring Boot + Gradle plugin | 4.1.1 | plugin `org.springframework.boot` 4.1.1 |
| `io.spring.dependency-management` plugin | 1.1.7 | `build.gradle` |
| Spring Framework | 7.0.9 | Boot BOM |
| Spring Security (incl. oauth2-resource-server, oauth2-jose) | 7.1.1 | Boot BOM |
| Nimbus JOSE + JWT | 10.9.1 | transitive of oauth2-jose |
| Spring Data JPA / Commons | 4.1.1 | Boot BOM |
| Hibernate ORM | 7.4.5.Final | Boot BOM |
| Jackson databind (Jackson 3, `tools.jackson.*`) | 3.1.5 | Boot BOM |
| Tomcat embedded | 11.0.24 | Boot BOM |
| HikariCP | 7.0.2 | Boot BOM |
| Micrometer core | 1.17.1 | Boot BOM |
| Logback / SLF4J | 1.5.38 / 2.0.18 | Boot BOM |
| Flyway core + `flyway-database-postgresql` | 12.4.0 | Boot BOM |
| PostgreSQL JDBC driver | 42.7.13 | Boot BOM |
| springdoc-openapi (`springdoc-openapi-starter-webmvc-ui`) | 3.1.1 *explicit* | `build.gradle` (3.x = Boot 4 line) |
| swagger-core-jakarta / swagger-ui webjar | 2.2.55 / 5.32.14 | transitive of springdoc |
| PostgreSQL (server) | 17 | `docker-compose.yml` `postgres:17` |
| JUnit Jupiter | 6.0.3 | Boot BOM |
| Mockito | 5.23.0 | Boot BOM |
| Testcontainers (`testcontainers-junit-jupiter`, `testcontainers-postgresql`) | 2.0.5 | Boot BOM (no explicit version in `build.gradle`) |
| Test database image | `postgres:17-alpine` | `AbstractIntegrationTest` |
| JaCoCo (Gradle `jacoco` plugin, `toolVersion`) | 0.8.15 *explicit* | to be added in Phase 0/7 (section 13) |
| OWASP dependency-check Gradle plugin `org.owasp.dependencycheck` | 13.0.0 *explicit* | to be added in Phase 7 (`09-SECURITY.md` section 12) |
| Runtime image | `eclipse-temurin:21-jre` (Ubuntu 26.04, contains `curl`, JRE 21.0.12) | `10-DEPLOYMENT.md` |
| Reverse proxy image | `caddy:2` | `10-DEPLOYMENT.md` |

Starters in use (all `4.1.1`): `actuator`, `data-jpa`, `flyway`, `oauth2-resource-server`, `security`, `validation`,
`webmvc`; test starters: `actuator-test`, `data-jpa-test`, `flyway-test`, `security-test`, `validation-test`, `webmvc-test`.
Rule: a dependency is added only after checking `build.gradle` and recording it as an ADR (new ADR number).

## 3. Module map

Package root `com.nexora`. Each module is `com.nexora.<module>.{api, application, domain, infrastructure}` (ADR-004).
`shared` has no business rules. A module may call another module only through the other module's `application` services and
DTO/value records; it references other modules' entities by UUID only (CONVENTIONS section 1).

| Module | Responsibility | Tables owned | Calls (application services) | Phase |
|---|---|---|---|---|
| `shared` | base entity, errors, security config, numbering, money/weight, paging, config | `business_number_sequences`, `idempotency_keys`, `audit_log` | none | 0 |
| `identity` | users, roles, permissions, login/refresh/logout, owner bootstrap | `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `refresh_tokens` | `shared` | 1 |
| `parties` | customers, suppliers, manufacturers, workers (one party, many roles), phones | `parties`, `party_roles`, `party_phone_numbers` | `shared` | 1 |
| `procurement` | purchases, material receipts, supplier returns | `purchases`, `material_receipts`, `supplier_returns` | `PartyService`, `InventoryService`, `BusinessNumberGenerator` | 2 |
| `inventory` | material movements (ledger) + balances (projection); **no public write endpoint** | `material_movements`, `inventory_balances` | `BusinessNumberGenerator` | 2 |
| `orders` | customer orders, items, delivery, order locking | `customer_orders`, `order_items` | `PartyService`, `InventoryService`, `BusinessNumberGenerator` | 3 |
| `finance` | accounts, financial transactions (ledger), payments, allocations, expenses, transfers | `financial_accounts`, `financial_transactions`, `payments`, `customer_payment_allocations`, `supplier_payment_allocations`, `manufacturer_payment_allocations`, `worker_payment_allocations`, `account_transfers`, `expenses` | `PartyService`, `BusinessNumberGenerator`, `IdempotencyService`, `AuditService` | 3, 5 |
| `production` | production batches (start/complete/cancel) | `production_batches`, `production_order_items` | `InventoryService`, `OrderService`, `BusinessNumberGenerator` | 4 |
| `outsourcing` | outsourcing jobs (issue/receive) | `outsourcing_jobs`, `outsourcing_order_items` | `PartyService`, `InventoryService`, `OrderService` | 4 |
| `workforce` | work records (rolling/warping) and worker payable | `work_records` | `PartyService` | 4 |
| `loans`, `chits` | loans, chits (money via `FinanceService`) | `loans`, `chits` | `PartyService`, `FinanceService` | 5 |
| `notifications` | due-date reminders, outbox of notifications, provider interface | `notifications`, `notification_configurations` | `finance`/`procurement` read services | 6 |
| `documents` | bill/statement upload metadata | `documents` | storage interface | 6 |
| `reporting` | dashboard + reports, **read-only SQL projections** | none (reads others through query services) | read-only query interfaces of other modules | 6 |

Boundary enforcement (new decision ADR-025): a module-boundary test added in Phase 2 (task 2.10 of the roadmap) fails the
build if a class outside `com.nexora.inventory` imports `com.nexora.inventory.infrastructure.*` or `.domain.*` entities, and
likewise for every module. Tool: Spring Modulith `ApplicationModules.of(NexoraApplication.class).verify()` if the Modulith
release matching Spring Boot 4.1 is on Maven Central at that time; otherwise a plain JUnit test that scans
`src/main/java` imports with `java.nio.file` and regex. Either way the test lives in `src/test/java/com/nexora/ModuleBoundaryTest.java`.

## 4. Layers and dependency direction

ADR-005 defines the responsibilities. Allowed dependencies (arrow = "may import"):

```
api  ─►  application  ─►  domain  ◄─  infrastructure
              │                           ▲
              └───────────────────────────┘   (application uses repository interfaces from infrastructure)
```
- `api` may import `application` DTO records and `shared`; never `infrastructure` or entities.
- `domain` may import `shared.domain`, `shared.error` and JPA annotations only; never `org.springframework.web.*`,
  `jakarta.servlet.*`, or any `infrastructure` class.
- Controller methods: at most 15 lines (validate via `@Valid` -> one service call -> map status).
- Entities: extend `shared.domain.AuditableEntity` (id UUID generated by Hibernate, `createdAt`, `updatedAt`, `createdBy`,
  `version`); protected no-arg constructor; static factory; named mutators; getters only (ADR-006).
- Repositories are public interfaces in `infrastructure` (except `shared.numbering`, package-private by design).

## 5. Cross-module contracts

These are the only ways modules touch each other's data. Names are fixed (other specs refer to them). All methods that write
must run inside the caller's transaction (`Propagation.MANDATORY`) so that a rollback undoes everything (ADR-011).

### 5.1 `BusinessNumberGenerator` (implemented, `com.nexora.shared.numbering`)
| Item | Value |
|---|---|
| Method | `String next(String prefix)` -> `PREFIX-YYYY-NNNN` (year from the injected `Clock`, zone Asia/Kolkata; `%04d`, grows past 9999) |
| Prefix rule | `^[A-Z]{2,10}$`, else `IllegalArgumentException` (programming error) |
| Transaction | `MANDATORY` (throws `IllegalTransactionStateException` outside one) |
| Locking | `INSERT ... ON CONFLICT DO NOTHING` for the (prefix, year) row, then `SELECT ... FOR UPDATE` (`PESSIMISTIC_WRITE`); counter increments inside the caller's transaction, so rollback gives gap-free numbers |
| Registered prefixes | `PTY, PUR, RCT, RTN, MOV, ORD, PAY, BAT, JOB, EXP, TRF, LON, CHT` |
| Rule | called as late as possible in the use case (see lock order, section 6) |

### 5.2 `PartyService` (`com.nexora.parties.application`, Phase 1)
| Item | Value |
|---|---|
| `PartyRef requireActiveWithRole(UUID partyId, PartyRole role)` | returns `PartyRef(id, code, name)` |
| Errors | `RESOURCE_NOT_FOUND` 404 (unknown id), `INVALID_PARTY_ROLE` 422 (party lacks the role), `PARTY_INACTIVE` 422 |
| Transaction | `@Transactional(readOnly = true)`; joins the caller's transaction |
| `PartyRef getRef(UUID partyId)` | name/code lookup for response DTOs; `RESOURCE_NOT_FOUND` if missing |
| Used by | procurement (SUPPLIER), orders (CUSTOMER), outsourcing (MANUFACTURER), workforce (WORKER), finance payments (role by direction), loans |

### 5.3 `InventoryService` (`com.nexora.inventory.application`, Phase 2) - the ONLY way stock changes (Rule R1)
| Item | Value |
|---|---|
| `MovementResult recordMovement(MovementCommand c)` | `MovementCommand(productType, toProductType, movementType, fromLocation, toLocation, quantityKg, movementDate, referenceType, referenceId, reason)`; `toProductType` is optional and defaults to `productType`; it differs only for `CONSUMPTION`/`OUTSOURCE_ISSUE` (raw silk leaves `RAW_STOCK`, the target `SILK_WARP`/`KORA_WARP` arrives in WIP; `04-DATA-MODEL.md` section 4.2); `reason` mandatory for `ADJUSTMENT` only |
| Validation (domain) | `quantityKg > 0` and scale 3; (from,to) pair legal for `movementType` per DOMAIN_RULES section 3 table; location/product pairs per `04-DATA-MODEL.md` section 4 (`RAW_STOCK`: raw silk and vuda warp; WIP and `FINISHED_STOCK`: silk and kora warp; `DIRECT_SALE` only for raw silk and vuda); `VUDA_WARP` may never be consumed, issued, or produced: movement types `CONSUMPTION`, `PRODUCTION_OUTPUT`, `OUTSOURCE_ISSUE`, `OUTSOURCE_RECEIPT`, or any movement touching `INTERNAL_WIP`/`EXTERNAL_WIP` with that product -> `INVALID_PRODUCT_FOR_OPERATION` 422 (DOMAIN_RULES section 4, Inventory) |
| Locking | For each **internal** location in the movement (`RAW_STOCK`, `INTERNAL_WIP`, `EXTERNAL_WIP`, `FINISHED_STOCK`): ensure the `inventory_balances(product_type, location)` row exists (`INSERT ... ON CONFLICT DO NOTHING`), then `SELECT ... FOR UPDATE`. When two internal rows are involved they are locked in ascending `(product_type, location)` order (the source row uses `productType`, the destination row `toProductType`). External locations (`SUPPLIER`, `CUSTOMER`, `WASTAGE`, `RETURNED`) have no balance row |
| Check | `balance(from) >= quantityKg` else `INSUFFICIENT_INVENTORY` 409 (message names available kg and location). `ADJUSTMENT` (user holding `INVENTORY_ADJUST`, `reason` required) is the only movement allowed to change stock without a business document, but it still cannot drive any balance below zero because the DB `CHECK (quantity_kg >= 0)` holds (ADR-026, DOMAIN_RULES section 4) |
| Writes (same transaction) | insert `material_movements` row (number `MOV-YYYY-NNNN` from `BusinessNumberGenerator`, `created_by`), update both balance rows (`quantity_kg`, `version`) |
| Read API | `BigDecimal available(ProductType, StockLocation)`; `List<StockLine> summary()`; `Page<MovementView> movements(filter, pageable)` (query side, no locks) |
| Transaction | `MANDATORY` for `recordMovement`; `readOnly` for reads |
| Forbidden | any other class writing `material_movements` or `inventory_balances`; any controller route that accepts a movement from a client |

### 5.4 `FinanceService` (`com.nexora.finance.application`, Phase 3) - the ONLY way money moves (Rule R1)
| Item | Value |
|---|---|
| `TransactionResult recordTransaction(TransactionCommand c)` | `TransactionCommand(accountId, direction IN/OUT, amount, transactionType, transactionDate, description, referenceNumber, source)` where `source` is exactly one typed reference: `paymentId`, `expenseId`, `loanId`, `chitId`, `accountTransferId`, or none for `OPENING_BALANCE`/manual |
| Validation | `amount > 0`, scale 2; exactly one source reference (or none for opening/manual) - also enforced by a DB CHECK (ROADMAP "known design gaps"); account exists and is `ACTIVE` |
| Locking | `SELECT ... FOR UPDATE` on the `financial_accounts` row (`PESSIMISTIC_WRITE`). Multi-account operations (transfers) lock accounts in **ascending UUID order** |
| Check (OUT) | `balance(account) >= amount` else `INSUFFICIENT_FUNDS` 409, for **CASH and BANK alike** (PLAN_DECISIONS Q4). `balance = SUM(IN) - SUM(OUT)` over that account's rows, computed by SQL after the lock is held |
| Writes | insert `financial_transactions` (append-only); no balance column exists on the account |
| Transaction | `MANDATORY` |
| Reversal | `reverse(transactionId)` (internal) inserts the opposite-direction row with `transactionType = REVERSAL` referencing the original; the original is never updated |

### 5.5 `OrderService` (`com.nexora.orders.application`, Phase 3/4)
`void lockForProduction(Collection<UUID> orderItemIds)` - marks the orders `IN_PROGRESS` + locked (named entity method, `ORDER_LOCKED` afterwards for edit/cancel); `OrderItemRef requireDeliverable(UUID itemId)`. Called by production and outsourcing; they never touch `customer_orders` directly.

### 5.6 Shared services (Phase 0-3)
| Service | Contract |
|---|---|
| `Clock` bean (`ClockConfig`) | `Clock.system(Asia/Kolkata)`; the only source of "now"/"today". Never call `Instant.now()`/`LocalDate.now()` in business code |
| `CurrentUser` / `AuthenticatedUserAuditor` | user id = JWT `sub`; auditor is empty outside a request (startup bootstrap, scheduled jobs) |
| `IdempotencyService` (`shared.idempotency`, Phase 3) | see section 7.2 |
| `AuditService` (`shared.audit`, Phase 3) | `void record(AuditAction action, String entityType, UUID entityId, String reason, Map<String,Object> details)`; writes `audit_log` in the caller's transaction (`MANDATORY`) |

## 6. Transactions and locking

ADR-010/011 give the strategy; this section adds the concrete per-resource rules and the global lock order.

Isolation `READ COMMITTED` (PostgreSQL default). One public service method = one use case = one `@Transactional` (never on
controllers, never on single repository calls). No external call inside a transaction. A transaction never waits on user input.

| Resource | Lock | Failure mapped to |
|---|---|---|
| `business_number_sequences` row | pessimistic (`FOR UPDATE`), held to commit | n/a (short wait) |
| `inventory_balances` row | pessimistic, ascending `(product_type, location)` | `INSUFFICIENT_INVENTORY` 409 |
| `financial_accounts` row | pessimistic, ascending `id` when several | `INSUFFICIENT_FUNDS` 409 |
| `parties` row (payments) | pessimistic on the paying/paid party for the duration of `recordPayment`, so two payments for one party cannot allocate the same outstanding twice (ADR-027) | `PAYMENT_ALLOCATION_EXCEEDED` 409 |
| `users` row (login) | pessimistic, so concurrent failed logins increment the counter exactly | n/a |
| `refresh_tokens` row (refresh) | pessimistic on the token row, so two simultaneous refreshes of one token cannot both succeed | `UNAUTHENTICATED` 401 |
| `idempotency_keys` | unique key insert; a concurrent duplicate waits on the first transaction, then replays | `DUPLICATE_REQUEST` 409 only if payload differs |
| Purchases, orders, batches, jobs, work records, master data | optimistic `@Version` | `CONCURRENT_MODIFICATION` 409 |

**Global lock acquisition order** (every use case that takes several locks must follow it; ADR-028):
1. `idempotency_keys` row (insert) 2. `parties` row 3. the aggregate row (`purchases`, `customer_orders`, `production_batches`,
`outsourcing_jobs`) 4. `inventory_balances` rows (ascending key) 5. `financial_accounts` rows (ascending id)
6. `business_number_sequences` rows (always last, so the hot counter row is held for the shortest time and can never be
part of a cycle).

Waiting is bounded: every connection sets `lock_timeout = 5s` and `statement_timeout = 30s` (JDBC URL option
`options=-c lock_timeout=5000 -c statement_timeout=30000`, set in the datasource configuration, not per query). A lock timeout
raises `CannotAcquireLockException`, mapped to **409 `CONCURRENT_MODIFICATION`** ("retry"). This handler is added in Phase 2.
HikariCP: `maximum-pool-size 10`, `connection-timeout 5s`. Tests prove each locking path with the concurrency pattern in
section 13.3, including a "remove the lock and watch the test fail" check.

## 7. Cross-cutting patterns

### 7.1 Error handling (implemented in `shared.error.GlobalExceptionHandler`)
Body is RFC 9457 `ProblemDetail` (`application/problem+json`) with extension members `code` (stable), `timestamp` (ISO-8601 UTC,
from the injected `Clock`), and for validation `errors[]` of `{field, message}` (ADR-013). 401/403 produced inside the security
filter chain use `ProblemJsonWriter` and are byte-compatible.

| Cause | HTTP | `code` | Implemented in |
|---|---|---|---|
| `BusinessException` (and subclasses `ResourceNotFoundException`, `InvalidStateTransitionException`, domain exceptions) | `ErrorCode.status()` | `ErrorCode.name()` | `handleBusiness` |
| `@Valid` body fails (`MethodArgumentNotValidException`) | 400 | `VALIDATION_FAILED` + `errors[]` | `handleMethodArgumentNotValid` |
| `@Validated` param constraint (`HandlerMethodValidationException`) | 400 | `VALIDATION_FAILED` + `errors[]` | `handleHandlerMethodValidationException` |
| Unreadable JSON / wrong types / unknown enum value in body or query / other 4xx raised by Spring MVC | 400 | `MALFORMED_REQUEST` | `handleExceptionInternal` |
| Unknown URL | 404 | `RESOURCE_NOT_FOUND` | `handleExceptionInternal` |
| Wrong HTTP method | 405 | `METHOD_NOT_ALLOWED` | `handleExceptionInternal` |
| Unsupported media type | 415 | `UNSUPPORTED_MEDIA_TYPE` | `handleExceptionInternal` |
| Bad `sort`/property (`org.springframework.data.core.PropertyReferenceException`) | 400 | `VALIDATION_FAILED` | `handleBadProperty` |
| `ObjectOptimisticLockingFailureException` | 409 | `CONCURRENT_MODIFICATION` | `handleOptimisticLock` |
| `DataIntegrityViolationException` with SQLSTATE `23505` | 409 | `DUPLICATE_RESOURCE` | `handleDataIntegrity` |
| `DataIntegrityViolationException` other SQLSTATE (FK/CHECK/NOT NULL) | 500 | `INTERNAL_ERROR` (a missed validation = a bug; logged) | `handleDataIntegrity` -> `handleGeneric` |
| `AccessDeniedException` from `@PreAuthorize` | 403 | `ACCESS_DENIED` | `handleAccessDenied` |
| No/invalid/expired token (filter chain) | 401 | `UNAUTHENTICATED` / `TOKEN_EXPIRED` | `SecurityErrorHandlers` |
| Authenticated but denied (filter chain) | 403 | `ACCESS_DENIED` | `SecurityErrorHandlers` |
| `CannotAcquireLockException`/`PessimisticLockingFailureException`, including the 5 s `lock_timeout` (Phase 2, ADR-030) | 409 | `CONCURRENT_MODIFICATION` | to add |
| Request body over the size limit (Phase 7) | 413 | `MALFORMED_REQUEST` | request-size filter |
| Rate limit exceeded (Phase 7, ADR-034) | 429 | `RATE_LIMITED` + `Retry-After` | rate-limit filter |
| Anything else | 500 | `INTERNAL_ERROR`; full exception logged with request id; body generic, never `ex.getMessage()` | `handleGeneric` |

Error code list: DOMAIN_RULES section 7, which includes `METHOD_NOT_ALLOWED` (405), `UNSUPPORTED_MEDIA_TYPE` (415),
`DUPLICATE_RESOURCE` (409) and `RATE_LIMITED` (429). Business rules never use try/catch for business errors in services.

### 7.2 Idempotency (ADR-018, Phase 3)
- Header `Idempotency-Key: <UUID>` (8-64 chars `[A-Za-z0-9-]`) is **required** on every write that moves money or a stock quantity
  a retry could duplicate (`05-API-SPEC.md` section 21, column "Key"): `POST /purchases/{id}/receipts`, `POST /purchases/{id}/returns`,
  `POST /orders/{id}/deliver`, `POST /payments`, `POST /payments/{id}/reverse`, `POST /finance/accounts/{id}/adjustments`,
  `POST /finance/transfers`, `POST /expenses`, `POST /expenses/{id}/reverse`, `POST /loans`, `POST /loans/{id}/principal-repayments`,
  `POST /loans/{id}/interest-payments`, `POST /loans/{id}/payments/{transactionId}/reverse`, `POST /chits/{id}/contributions`,
  `POST /chits/{id}/payout`, `POST /chits/{id}/organiser-payments`, `POST /chits/{id}/entries/{transactionId}/reverse`,
  `POST /outsourcing-jobs/{id}/receive` (POST-MVP additions: item deliveries, order returns, refunds). State-machine actions that cannot
  repeat (start, issue, confirm, cancel) do not take a key. Missing -> 400 `VALIDATION_FAILED`.
- Table `idempotency_keys(id, idempotency_key, user_id, request_method, request_path, request_hash VARCHAR(64) SHA-256 of the raw body,
  response_status, response_body TEXT, created_at, expires_at = created_at + 48h)`, `UNIQUE (user_id, idempotency_key)` (`04-DATA-MODEL.md` section 8.4).
- Flow inside the use-case transaction: insert the key (unique) -> run the use case -> store status + body. Same key + same hash ->
  stored response replayed with header `Idempotency-Replayed: true`. Same key + different hash -> 409 `DUPLICATE_REQUEST`.
  A concurrent duplicate blocks on the unique index until the first transaction commits, then replays.
- A rolled-back use case rolls back the key row, so a client retry after a failure runs normally.
- A daily scheduled job deletes rows with `expires_at < now()`.

### 7.3 Logging
- Format: plain text in `dev`; **JSON** in `prod` via Spring Boot structured logging (`logging.structured.format.console: ecs`).
- A `RequestIdFilter` (highest precedence, `shared.web`) puts `requestId` and (after authentication) `userId` into the SLF4J MDC and
  returns `X-Request-Id`. An inbound `X-Request-Id` is accepted only if it matches `^[A-Za-z0-9-]{8,64}$`, otherwise a new UUID is used.
- One INFO line per state-changing use case: `event=<name> number=<business number> actor=<userId> requestId=<id>`; amounts and
  weights may be logged, party names and phone numbers may not.
- Levels: `prod` root INFO, `com.nexora` INFO; `dev` `com.nexora` DEBUG; tests WARN. SQL logging is off everywhere except a
  developer's local override.
- **Never log:** passwords, password hashes, access/refresh tokens, `Authorization` header, `JWT_SECRET`, DB credentials,
  full bank account numbers (only the last 4), request bodies of `/auth/**`, stack traces in HTTP responses.

### 7.4 API conventions
CONVENTIONS section 4 and ADR-012 apply. Additions: pagination `page` (0-based), `size` default 20, **max 100**
(`spring.data.web.pageable.max-page-size: 100`), `sort=<property>,<asc|desc>` limited to documented properties (an unknown
property is 400, not 500); dates `yyyy-MM-dd`, timestamps ISO-8601 UTC; money and weights are JSON numbers with exactly
2 / 3 decimals (never strings, never floats); `201` + `Location` on create; actions that return no body return `204`; PATCH
semantics = only supplied fields change; no endpoint accepts derived values (stock, balances, outstanding, status of payment).

## 8. Configuration

Profiles: `dev` (default, `spring.profiles.default`), `test`, `prod`. Common file `application.yml`; profile files override.
Every secret is an environment variable; prod has **no defaults** for credentials or the JWT secret (fail fast).

| Env var | Property | Used by | dev default | prod |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | - | profile | (none: `dev`) | `prod` |
| `SERVER_PORT` | `server.port` | HTTP | 8080 | 8080 |
| `DB_URL` | `spring.datasource.url` | runtime DB | `jdbc:postgresql://localhost:5431/nexora_db` | required |
| `DB_USERNAME` / `DB_PASSWORD` | `spring.datasource.username/password` | runtime DB role `nexora_app` | `postgres` / `nexora_dev` | required |
| `FLYWAY_URL` / `FLYWAY_USER` / `FLYWAY_PASSWORD` | `spring.flyway.url/user/password` | migration connection, role `nexora_owner`, **without** the lock/statement timeouts | falls back to datasource | required |
| `JWT_SECRET` | `nexora.security.jwt-secret` | HS256 key, >= 32 bytes | dev-only constant | required |
| `JWT_ACCESS_TTL` / `JWT_REFRESH_TTL` | `nexora.security.access-token-ttl` / `refresh-token-ttl` | token lifetimes | 15m / 7d | 15m / 7d |
| `LOGIN_MAX_FAILURES` / `LOGIN_LOCKOUT` | `nexora.security.max-failed-logins` / `lockout-duration` | brute-force lock | 5 / 15m | 5 / 15m |
| `BOOTSTRAP_OWNER_USERNAME` / `BOOTSTRAP_OWNER_PASSWORD` | `nexora.bootstrap.owner-username/password` | first OWNER, created only if `users` is empty | `owner` / dev constant | required on first start, then removed |
| `CORS_ALLOWED_ORIGINS` | `nexora.cors.allowed-origins` | browser clients | empty | empty (no browser client in the MVP) |
| `NVD_API_KEY` | - | CI dependency audit | - | CI secret |
| `JAVA_OPTS` | - | JVM flags | - | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` |

Known mismatches in the current repository (to fix in Phase 0/1 tasks): `application.yml` does not yet define
`nexora.security.jwt-secret` (add `${JWT_SECRET}` in the common file and a dev default in `application-dev.yml`);
`application-test.yml` points at the local dev database and is unused by tests (integration tests use Testcontainers on
the default `dev` profile) - keep the file only for developers who run the app with `SPRING_PROFILES_ACTIVE=test`;
`.env.example` holds only a dummy `JWT_SECRET` value - the complete file is in `10-DEPLOYMENT.md`.

## 9. Persistence rules (summary of CONVENTIONS section 8, ADR-016)
Flyway only; `ddl-auto: validate`; `open-in-view: false`; `hibernate.jdbc.time_zone: UTC`; every FK indexed; named constraints
(`pk_ fk_ uq_ ck_ ix_`); enum columns `VARCHAR` + CHECK; money `NUMERIC(14,2)`, weights `NUMERIC(12,3)`; `version BIGINT` on editable
aggregates; ledger tables (`material_movements`, `financial_transactions`, `audit_log`) are append-only, and in production the runtime
role has `INSERT, SELECT` only on them (`09-SECURITY.md` section 10). Native SQL uses named parameters only; `LIKE` input is escaped.
Migration numbering: V1, V2 (smoke), V3 foundation, V4 numbering, V5 identity; the next migration is `V6`.

## 10. Observability
- Actuator exposure: `health`, `info` (Phase 0). Phase 7 adds `metrics` and `prometheus` (dependency `micrometer-registry-prometheus`,
  BOM-managed) on a **management port 8081 that is not published outside the compose network**.
- Health groups: `/actuator/health/liveness` (JVM up), `/actuator/health/readiness` (DB reachable); `show-details: always` only in `dev`,
  `never` in `prod`.
- Metrics to expose: HikariCP pool (built in), HTTP server requests (built in), and custom counters
  `nexora.stock.rejected{reason}`, `nexora.payment.recorded`, `nexora.login.failed`, `nexora.idempotency.replayed`.
- Alerts are defined in `10-DEPLOYMENT.md` section 11.

## 11. Security architecture (summary)
Stateless chain, no sessions, CSRF off (bearer tokens only); public paths: `/api/v1/auth/login|refresh|logout`, `/actuator/health/**`,
`/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**` (the last two return 404 when springdoc is disabled, i.e. outside `dev`);
everything else authenticated; permission checks with `@PreAuthorize("hasAuthority('<PERMISSION>')")` and `@EnableMethodSecurity`.
Details, matrix, and tests: `09-SECURITY.md`.

## 12. Testing architecture (summary; strategy in CONVENTIONS section 9 and ADR-015)
| Level | Tool | Package / location | Database |
|---|---|---|---|
| Domain invariants, helpers | JUnit 5 + AssertJ | `src/test/java/com/nexora/<module>/domain`, `shared/**` | none |
| Use cases and migrations | `@SpringBootTest` extending `com.nexora.support.AbstractIntegrationTest` | `.../<module>/application` | Testcontainers `postgres:17-alpine`, one shared container per JVM |
| HTTP behaviour, validation, 401/403 | `@SpringBootTest` + `@AutoConfigureMockMvc` + real JWT from login | `.../<module>/api` | same container |
| Concurrency | integration test, N threads, `CountDownLatch` start gun, `TransactionTemplate` | `.../<module>/application` | same container |
| Scenario ("end-to-end") | MockMvc chain across modules | `src/test/java/com/nexora/scenario` | same container |

Details (naming, coverage threshold 80 %, traceability): `08-TESTING.md`.

## 13. Build and quality gates
Commands from `Nexora-backend/`: `./gradlew build` (compile + all tests + JaCoCo verification + jar), `./gradlew test`,
`./gradlew bootRun`. JaCoCo rule (new decision ADR-029): `jacocoTestCoverageVerification` fails the build if LINE coverage is
below **0.80** for classes matching `com/nexora/*/application/**` and `com/nexora/*/domain/**`; `check` depends on it.
The OWASP dependency-check task runs in CI (`09-SECURITY.md` section 12). CI definition: `.github/workflows/ci.yml` (extended in
`10-DEPLOYMENT.md` section 6).

## 14. Boot 4 pitfalls (verified in this repository)

| Pitfall | Correct form |
|---|---|
| `javax.*` | `jakarta.*` only (`jakarta.persistence`, `jakarta.validation`, `jakarta.servlet`) |
| Jackson 2 packages | Jackson 3 databind = `tools.jackson.*` (e.g. `tools.jackson.databind.json.JsonMapper`, used by `ProblemJsonWriter`); annotations stay `com.fasterxml.jackson.annotation.*` |
| `PropertyReferenceException` | `org.springframework.data.core.PropertyReferenceException` (not `org.springframework.data.mapping`) |
| `@AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |
| Testcontainers 1.x | Testcontainers 2.x: `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic), artifacts `testcontainers-postgresql` and `testcontainers-junit-jupiter` |
| `@Id` / `@Version` ambiguity | `jakarta.persistence.*` and `org.springframework.data.annotation.*` both define `Id` and `Version`; with two wildcard imports the compiler reports ambiguity, and Hibernate honours only the jakarta ones. Import `jakarta.persistence.Id` and write `@jakarta.persistence.Version` |
| Auditing annotations | `@CreatedDate`/`@LastModifiedDate` fill timestamps; `@CreatedBy`/`@LastModifiedBy` fill users from `AuditorAware`. `@LastModifiedBy` on an `Instant` silently leaves `updated_at` null |
| `Clock` | inject `java.time.Clock`; `io.micrometer.core.instrument.Clock` is a different class on the classpath |
| Catch-all `@ExceptionHandler(Exception.class)` | the advice must extend `ResponseEntityExceptionHandler`, otherwise Spring's own 404/405/400 become 500 |
| 422 constant | `HttpStatus.UNPROCESSABLE_CONTENT` |
| Security DSL | `SecurityFilterChain` bean with lambda DSL; no `WebSecurityConfigurerAdapter`, `antMatchers`, `.and()` |
| Security + springdoc | docs paths must be `permitAll` in the chain (they return 404 when the feature is off) |
| `FOR UPDATE` with outer join fetch | PostgreSQL rejects `FOR UPDATE` on the nullable side of an outer join: lock the row with a plain query, then load associations in the same transaction |
| Module starters | modular: `spring-boot-starter-webmvc`, `-flyway`, `-oauth2-resource-server`, test starters per feature |
| Stale server | a leftover `bootRun` on port 8080 makes new code look broken: `ss -ltnp \| grep 8080` |
| `bootRun` progress | Gradle shows ~80 % while the server runs; that is normal |

## 15. Environments (summary; full detail in `10-DEPLOYMENT.md`)
`dev`: developer machine, `docker compose up -d` in `Nexora-backend/` (PostgreSQL 17 on host port 5431), Swagger UI on.
`test`: CI and local test runs, Testcontainers, no external services. `prod`: Docker compose on a VPS behind Caddy, Swagger off,
JSON logs, credentials from the environment.

## 16. Decisions made while writing

The decisions taken while writing this file are recorded as **ADR-025 to ADR-034 in `DECISIONS.md`** (module-boundary test,
non-negative adjustments, party lock for payments, global lock order, coverage gate, DB session timeouts, no overdraft, two database
roles, idempotency on retry-sensitive POSTs, rate limiting). `DECISIONS.md` is the single home of the ADR log.

Gaps found between documents and code while writing (fix as part of the named tasks): `application.yml` lacks the JWT secret
property (Phase 1, task 1.4); `application-test.yml` is unused (Phase 0 clean-up); `docker-compose.yml` requires `DB_PASSWORD` but
the app's dev default password is `nexora_dev` while `.env.example` suggests `change-me` (document: copy `.env.example` to
`.env` and keep both equal); `ROADMAP.md` mentions `Idempotency-Key` for "receipts" only - this file extends it to the seven
endpoints above.
