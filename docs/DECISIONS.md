# Architecture & Technology Decisions (ADR log)

Every significant choice in Nexora/SmartSilk, **why** it was made, what was rejected, and
what it costs us. Format: lightweight ADRs.

- **Status:** `Accepted` (in force) · `Proposed` (recommended, decide when the phase starts) · `Superseded`
- Adding or changing a decision? Append a new ADR and don't rewrite history. Use `/explain <topic>` to have the AI walk through any of them.

---

## ADR-001 Modular monolith, not microservices — Accepted
**Context:** One developer, one business, one database, strongly coupled money and inventory transactions.
**Decision:** A single Spring Boot application split into modules by business capability (`parties`, `procurement`, `inventory`, …).
**Why:** Microservices bring network failures, distributed transactions, service discovery and multiple deployments. None of these problems exist here. A modular monolith keeps ACID transactions across modules and still allows extracting a module later.
**Rejected:** Microservices, Kafka, Kubernetes, CQRS/event sourcing (design doc §6.25).
**Cost:** Module boundaries have to be kept by discipline (see ADR-004), because nothing physical enforces them.

## ADR-002 Java 21 + Spring Boot 4 — Accepted
**Why Java/Spring:** A mature ecosystem for transactional business systems (Spring Data JPA, Security, Validation, Flyway, Actuator), strong demand in the job market, and the developer's learning goal.
**Why Java 21:** Current LTS. Records (DTOs), pattern matching, sealed types and virtual threads are available.
**Why Boot 4.x:** Latest generation (Spring Framework 7, Jakarta EE 11, Hibernate 7, Spring Security 7, Jackson 3). Starting a new product on the previous major would mean an upgrade soon after launch.
**Watch out:** Most tutorials and AI training data describe Boot 2/3. Differences that matter:
- Jackson 3: databind packages are `tools.jackson.*`. Annotations stay `com.fasterxml.jackson.annotation.*`.
- Security: lambda DSL only. `WebSecurityConfigurerAdapter` is gone. Use a `SecurityFilterChain` bean.
- Starters are modular (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, and matching `-test` starters).
- `javax.*` is dead. Everything is `jakarta.*`.

## ADR-003 PostgreSQL 17 — Accepted
**Why:** Full ACID guarantees, row-level locking (`SELECT … FOR UPDATE`), `NUMERIC` for exact money, CHECK constraints, partial indexes, and window functions for reports. Free, and easy to get managed hosting.
**Rejected:** MySQL (weaker CHECK/partial index story), MongoDB (a financial ledger is relational and needs multi-document transactions), H2 for tests (different SQL and locking semantics, see ADR-015).

## ADR-004 Package-by-module with internal layers — Accepted
```
com.nexora.<module>/{api, application, domain, infrastructure}
```
**Why:** "Everything about purchases lives in `procurement`." This prevents a global `service/` folder with 40 interlinked classes and keeps a later extraction possible.
**Rule:** Other modules may use only a module's `application` service interfaces and its DTOs, never its repositories or entities.
**Enforcement (Proposed, Phase 2):** Spring Modulith `ApplicationModules.of(NexoraApplication.class).verify()` in a unit test. It fails the build when a module reaches into another module's internals.

## ADR-005 Layer responsibilities — Accepted
| Layer | Owns | Must not |
|---|---|---|
| `api` (controller + request/response DTOs) | HTTP, Bean Validation, auth annotations, status codes | Business logic, repositories |
| `application` (use-case services) | Orchestration, `@Transactional`, locking, calling other modules | HTTP types, SQL strings |
| `domain` (entities, value objects, domain services, enums, exceptions) | Invariants and state transitions (`purchase.recordReceipt(kg)`) | Spring web, external APIs |
| `infrastructure` (repositories, adapters, schedulers) | Persistence, external providers | Business decisions |
| Database | Structural integrity: FK, UNIQUE, CHECK, NOT NULL | — (the last line of defence, not the only one) |

**Golden question:** Is it HTTP? → api. Coordinating steps? → application. A business invariant? → domain. Structural? → DB.

## ADR-006 JPA entities double as the domain model ("rich entities") — Accepted
**Decision:** No separate domain/persistence object pairs. JPA entities are the domain model, but:
no public setters, a protected no-arg constructor for JPA, intention-revealing methods (`lock()`, `complete(output, wastage)`), and invariants checked inside those methods.
**Why:** Separate models double the code for a small team. Rich entities keep the rules in one place.
**Rejected:** Anaemic `@Getter @Setter` entities with logic in services, and full hexagonal mapping layers.
**Cost:** Some JPA leakage into the domain (annotations). Accepted.

## ADR-007 UUID primary keys + human business numbers — Accepted
**Why UUID:** Not guessable, safe to expose in URLs, no row-count leakage, can be generated in the app.
**Why business numbers (`PUR-2026-0001`):** The owner reads them aloud on the phone and prints them on bills and WhatsApp messages.
**Generation:** A `business_number_sequences(prefix, year, next_value)` table, incremented with `SELECT … FOR UPDATE` inside the use-case transaction. The counter resets per year and is gap-free when a transaction rolls back (bills may need that for GST later).
**Rejected:** Native Postgres sequences (gaps on rollback, no yearly reset), `MAX()+1` (race condition).

## ADR-008 Exact numeric types — Accepted
Money `NUMERIC(14,2)` ↔ `BigDecimal` (scale 2). Weight `NUMERIC(12,3)` ↔ `BigDecimal` (scale 3). Rounding is `HALF_UP`, applied explicitly with `setScale`. **Never** `double`/`float`.
Compare with `compareTo`, never `equals` (`2.0` ≠ `2.00` under `equals`).
Business dates use `DATE` ↔ `LocalDate`. System timestamps use `TIMESTAMPTZ` ↔ `Instant`. "Today" comes from an injected `Clock` in the `Asia/Kolkata` zone, so it is testable.

## ADR-009 Ledgers as the source of truth — Accepted
Inventory = `material_movements`. Money = `financial_transactions`. Both are append-only.
`inventory_balances(product_type, location, quantity_kg, version)` is a **projection** that exists to give the lock a stable row and to make reads fast. It is updated in the same transaction as the movement and can be rebuilt from movements.
Balances, outstanding and payable are **computed** (DOMAIN_RULES §6).
**Why:** The owner can ask "why is stock 50 kg?" and the system can show every movement. Corrections are visible.
**Rejected:** A mutable `stock` column (no audit trail), a full double-entry accounting engine (too much for the MVP, and possible later).

## ADR-010 Concurrency strategy — Accepted
| Resource | Strategy |
|---|---|
| Inventory balance | **Pessimistic** `PESSIMISTIC_WRITE` on the `inventory_balances` row |
| Cash/bank for transfers and cash-outs | Pessimistic lock on account rows, **ascending id order** |
| Orders, purchases, jobs, batches (editable aggregates) | **Optimistic** `@Version` → 409 `CONCURRENT_MODIFICATION` |
| Master data | Optimistic `@Version` |
Isolation stays at PostgreSQL's default `READ COMMITTED` with explicit locks, not SERIALIZABLE everywhere.
**Rules:** transactions stay short, there is never an external call inside a transaction, and a transaction never waits on user input.

## ADR-011 Transaction boundary = one use case — Accepted
`@Transactional` goes on the **application service method** (`receiveMaterial`, `recordCustomerPayment`), never on controllers or individual repository calls. Read-only services use `@Transactional(readOnly = true)`. `spring.jpa.open-in-view=false` is already set, so lazy loading outside a transaction fails fast instead of silently issuing queries.

## ADR-012 REST API conventions — Accepted
`/api/v1/...` with resource nouns. Business actions are sub-resources (`POST /purchases/{id}/receipts`, `POST /production-batches/{id}/complete`). Lists are paginated (`page`, `size`, `sort`). Clients send intent, never derived numbers. Details: `CONVENTIONS.md §4`.
**Rejected:** GraphQL and gRPC. The Android client is the only consumer and the operations are command-shaped.

## ADR-013 Error format: RFC 9457 `ProblemDetail` + stable `code` — Accepted
The design doc specifies `{timestamp, status, code, message, path}`. We implement it with Spring's built-in `ProblemDetail` (standard fields `status`, `title`, `detail`, `instance`) plus the extension properties `code` and `timestamp`, and `errors[]` for field validation.
**Why:** It follows a standard, Spring produces it natively, and it still carries everything the design asked for. The Android app switches on `code`.

## ADR-014 Authentication: Spring Security + JWT access token + opaque refresh token — Accepted (design), details Proposed
- Access token: a **JWT signed with HS256** (secret from an env var), ~15 min lifetime, claims `sub`, `roles`, `permissions`, `iat`, `exp`. Issued with `NimbusJwtEncoder` and validated by `spring-boot-starter-oauth2-resource-server`. This is Spring's own JWT support, so no extra JWT library is needed.
- Refresh token: a **random opaque string**. Only its **SHA-256 hash** is stored in `refresh_tokens` (with expiry and a revoked flag). It is **rotated** on every refresh, and logout revokes it.
- Passwords: `BCryptPasswordEncoder` (via `DelegatingPasswordEncoder`).
- Authorization is **permission-based** (`@PreAuthorize("hasAuthority('PAYMENT_CREATE')")`). Roles (`OWNER`, `STAFF`) are just permission bundles. The MVP seeds only `OWNER` (NFR 20.3).
**Rejected:** Sessions/cookies (the mobile client is stateless), Keycloak/OAuth server (overkill), long-lived access tokens.
**Why the refresh token is not a JWT:** it has to be revocable, which requires a DB lookup anyway, so an opaque token is simpler and safer.

## ADR-015 Testing: JUnit 5 + AssertJ + Testcontainers PostgreSQL — Accepted
- **Unit tests** for domain entities and rules (fast, no Spring).
- **Integration tests** (`@SpringBootTest` + Testcontainers Postgres with `@ServiceConnection`) for every use case, including Flyway migrations. They run against the real database.
- **Concurrency tests** for stock and payments (two threads, one must fail with 409).
- **Web slice tests** (`@WebMvcTest`) for status codes, validation and security rules.
**Why not H2:** It would test a different database. Locking, `NUMERIC`, CHECK constraints and SQL dialect all differ.

## ADR-016 Flyway migrations — Accepted
Schema changes happen **only** through `V<n>__<description>.sql`. `ddl-auto=validate`, so Hibernate never changes the schema.
**Rule:** Once a migration is merged to `main` or has run anywhere shared, it is **immutable**. Fix forward with a new version. Before that, you may edit it and reset your local DB.

## ADR-017 API documentation: springdoc-openapi — Proposed (Phase 0)
Swagger UI at `/swagger-ui.html` in dev, disabled in prod. It is the contract for the Android client. Use the springdoc major version that supports Spring Boot 4 (3.x line). Verify on the springdoc releases page.

## ADR-018 Idempotency keys — Accepted (design), implemented Phase 3
An `idempotency_keys(key, user_id, request_hash, response_status, response_body, created_at)` table. A filter or aspect on annotated endpoints stores the result in the **same transaction** as the use case. A replay returns the stored response. Records expire after ~24–48h.
**Why:** A payment retried on a flaky mobile network must not be recorded twice.

## ADR-019 Side effects after commit — Accepted
Notifications, WhatsApp and PDF generation are triggered by internal application events with `@TransactionalEventListener(phase = AFTER_COMMIT)`, backed by a `notifications` table that holds status and retries (a simple outbox). A WhatsApp outage never rolls back a payment.
**Proposed:** Spring Modulith's event publication registry could provide this outbox for free (Phase 6).

## ADR-020 Scheduling: Spring `@Scheduled` — Accepted
For due-date reminders and similar jobs. It runs as a single instance. If we ever run more than one instance, add ShedLock. No Quartz or Kafka.

## ADR-021 Configuration & secrets — Accepted
`application.yml` holds safe defaults. Profiles are `dev`, `test` and `prod`. Secrets (DB password, JWT secret) come **only** from environment variables in prod and never from committed files. The dev password committed today is acceptable only because the database is local.

## ADR-022 Observability — Proposed (Phase 7)
Actuator (`health`, `info`, `metrics`, `prometheus`) is exposed on the management port and protected. Logs are JSON structured (`logging.structured.format.console`). Every request gets a correlation id (MDC) returned as `X-Request-Id`. Passwords, tokens and full account numbers are never logged.

## ADR-023 Deployment target — Proposed (decide at Phase 8)
**Recommendation:** A Docker image (multi-stage, or `./gradlew bootBuildImage`) and `docker compose` on a small VPS: app + PostgreSQL + **Caddy** (automatic HTTPS). CI/CD runs on **GitHub Actions** and pushes the image to GHCR. **Nightly `pg_dump` to off-site storage, with a tested restore.**
**Alternative:** A PaaS (Render/Railway/Fly) + managed Postgres. Less ops work, higher monthly cost, less learning.
**Why not Kubernetes:** One app and one database, so it's not justified.
**Non-negotiable for a real financial system:** automated backups, restore drills, HTTPS, secrets in env vars.

## ADR-024 Android client: Kotlin + Jetpack Compose + MVVM — Proposed (Track B)
Compose UI → ViewModel (StateFlow) → Repository → Retrofit/OkHttp (or Ktor) client. Hilt for DI. Tokens are kept in DataStore, encrypted with an Android Keystore key. An OkHttp `Authenticator` handles 401 → refresh → retry. Errors map by `code`.
The client **never** re-implements business rules. It only displays what the backend computes.

---
### Decisions deliberately NOT made yet (and why)
- **Redis/caching** — no measured need. Add only when a query is proven slow.
- **FIFO costing / raw material lots** — the business doesn't need lot traceability yet (design §4.11.4).
- **Double-entry accounting** — the MVP uses the transaction ledger. It could be layered on later from `financial_transactions`.
- **Multi-tenant / multi-company** — single business by requirement.
