# Learning Notes — SmartSilk (Nexora)

Personal study notes, written as the project is built. Each section matches a roadmap task.
Format: concept → line-by-line walkthrough → answers to the review questions → interview line.
Append a new section after every task.

---

## Part 0 — Java and Spring words you will see everywhere

| Word | Meaning | Example in this project |
|---|---|---|
| **package** | A folder-like namespace for classes. Must match the folder path. | `package com.nexora.shared.domain;` lives in `src/main/java/com/nexora/shared/domain/` |
| **import** | Lets you use a class from another package without writing its full name. | `import java.util.UUID;` |
| **class** | A blueprint for objects: fields (data) plus methods (behaviour). | `AuditableEntity` |
| **abstract class** | A class you cannot create with `new`; it exists only to be extended. | `AuditableEntity` |
| **extends** | "Inherits from". The child gets the parent's fields and methods. | `class Party extends AuditableEntity` |
| **field** | A variable that belongs to an object. | `private UUID id;` |
| **private / protected / public** | Who may access it: only this class / this class plus subclasses and same package / everyone. | fields are `private`, getters `public` |
| **getter** | A method that returns a private field. `getId()` returns `id`. | |
| **setter** | A method that changes a field. We deliberately do NOT add them to entities, so state changes only through named methods that protect business rules. | |
| **annotation** | A label starting with `@` that tells a framework what to do with the code under it. It does not run by itself; a framework reads it. | `@Id`, `@Version` |
| **lambda** | A short inline function: `() -> Optional.empty()` means "a function with no input that returns an empty Optional". | |
| **Optional<T>** | A box that holds either one value or nothing. Replaces returning `null`. | `Optional.empty()` |
| **UUID** | A 128-bit random identifier, written like `3f2b8c1e-...`. | entity ids |
| **Instant** | A moment on the timeline in UTC, with no time-zone confusion. | `createdAt` |
| **Generics `<UUID>`** | Fills in a type parameter. `AuditorAware<UUID>` means "an auditor-provider whose user id is a UUID". | |

### Spring concepts

- **Bean:** an object that Spring creates, stores and manages for you. You do not write `new` for it.
- **IoC container / ApplicationContext:** the box where Spring keeps all beans. At startup it scans your packages (`com.nexora` and below, because `NexoraApplication` lives there), finds the annotated classes, and builds the beans.
- **Dependency injection (DI):** a class says what it needs, usually in its constructor, and Spring supplies it. This is why the project uses constructor injection and not `new`, which makes testing and swapping implementations easy.
- **`@Configuration`:** marks a class that contains instructions for building beans.
- **`@Bean`:** put on a method inside a `@Configuration` class. The method's return value becomes a bean.
- **JPA:** a Java standard (Jakarta Persistence) describing how to map Java objects to database tables.
- **Hibernate:** the library that implements JPA. It writes the SQL for you.
- **Entity:** a Java class mapped to a table. One object = one row.
- **Persistence context:** Hibernate's per-transaction memory of the entities it has loaded, used to detect changes and write them back at commit.
- **Spring Data JPA:** adds repositories (`save`, `findById`, ...) on top of JPA, plus features like auditing.

---

## Part 1 — Tasks 0.2 and 0.3 recap (config)

### Spring profiles (0.2)
- `application.yml` is always loaded. `application-<profile>.yml` loads only when that profile is active and overrides the common file.
- `spring.config.activate.on-profile: dev` at the top of a profile file means "apply this file only for dev".
- `spring.profiles.default: dev` = the profile used when nothing else is set. `SPRING_PROFILES_ACTIVE=prod` overrides it. Do not use `active:` in the yml, as it can silently beat the environment variable.
- `${DB_URL:jdbc:...}` = read env var `DB_URL`; if missing, use the text after the colon. `${DB_URL}` with no fallback = fail at startup if missing. This is **fail fast**, and it is why the prod check failing was a PASS.
- `ddl-auto: validate` = Hibernate only checks that entities match the schema. Flyway is the only thing that changes the schema.
- `open-in-view: false` = the database session is not kept open into the web layer, which prevents hidden lazy-loading queries during JSON rendering.
- `time_zone: UTC` = timestamps are stored and read in UTC.

### Why Gradle stops at 80% (bootRun)
`bootRun` starts the server and does not finish until the server stops. The progress bar waits on that last task. It is not a hang. Confirm with `curl localhost:8080/actuator/health`.

### .env.example (0.3)
- `.env.example` is committed, with placeholder values. It documents which variables exist.
- `.env` holds real values and is gitignored (`.gitignore:37`).
- Spring does not read `.env` on its own. You export the variables in the shell, or the deploy tool injects them.
- Interview line: "Secrets never live in the repo. The repo documents the variable names; values come from the environment."

### Review answers (Blocks 1 and 2)
- 404 on `/api/v1/anything`: correct, because no controller maps that URL yet.
- Prod run failing with `Could not resolve placeholder 'DB_URL'`: correct, because prod has no default DB settings, by design.
- `actuator/health` showing `components`: only because dev sets `show-details: always`. In prod the details stay hidden so internals do not leak.

---

## Part 2 — Task 0.4: AuditableEntity and JPA auditing

### 2.1 What problem does it solve?
Every table needs: an id, when it was created, when it was last updated, who created it, and a version number for conflict detection. Writing that into 30+ entities invites mistakes, and this is real money. So we write it once in a parent class.

### 2.2 `AuditableEntity.java` — line by line

Location: `Nexora-backend/src/main/java/com/nexora/shared/domain/AuditableEntity.java`

```java
package com.nexora.shared.domain;
```
Declares the package. It must match the folder path. `shared` means every module may use it. `domain` is where domain/base classes go per CONVENTIONS §1.

```java
import jakarta.persistence.*;
```
Imports the JPA annotations: `@MappedSuperclass`, `@EntityListeners`, `@Id`, `@GeneratedValue`, `@Column`, `@Version`, and `GenerationType`. It must be `jakarta`, never `javax`. Spring Boot 4 only knows `jakarta`.

```java
import org.springframework.data.annotation.*;
```
Imports `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy` (Spring Data's auditing annotations). They are different annotations from the JPA ones, even though they sit in the same class.

```java
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
```
The listener class that does the auditing work.

```java
import java.time.Instant;
import java.util.UUID;
```
The two types used for timestamps and ids.

```java
@MappedSuperclass
```
Says "this class is not a table. Copy its fields into the table of every entity that extends it". Without it, JPA would ignore these fields in the children.

```java
@EntityListeners(AuditingEntityListener.class)
```
Registers a listener, an object that Hibernate calls at lifecycle moments (before insert, before update). `AuditingEntityListener` looks for `@CreatedDate`, `@LastModifiedDate` and `@CreatedBy` fields and fills them. Without this line, those annotations do nothing.

```java
public abstract class AuditableEntity {
```
`public` = visible to all modules. `abstract` = you can never write `new AuditableEntity()`; it only makes sense as a parent.

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;
```
- `@Id` marks the primary key.
- `@GeneratedValue(strategy = UUID)` makes Hibernate generate a random UUID in Java just before the insert. The DB default `gen_random_uuid()` still exists as a safety net for raw SQL inserts.
- `private`: only this class touches the field directly. Others read it through `getId()`.
- Why UUIDs: they are safe to create anywhere, do not reveal how many rows exist, and cannot collide when data is merged later.

```java
@CreatedDate
@Column(name = "created_at", nullable = false, updatable = false)
private Instant createdAt;
```
- `@CreatedDate` = the listener sets this on first save.
- `name = "created_at"` maps the Java name `createdAt` to the SQL column `created_at`.
- `nullable = false` = Hibernate expects NOT NULL.
- `updatable = false` = Hibernate never includes this column in an UPDATE statement, so history cannot be rewritten.
- `Instant` = a UTC moment. It matches `timestamptz` and the `UTC` setting from 0.2. `LocalDateTime` has no zone, which causes classic "off by 5.5 hours" bugs.

```java
@LastModifiedDate
@Column(name = "updated_at", nullable = false)
private Instant updatedAt;
```
The listener sets it on insert and refreshes it on every update.

```java
@CreatedBy
@Column(name = "created_by", updatable = false)
private UUID createdBy;
```
The listener asks the `AuditorAware` bean (File 2) "who is the current user?" and stores the answer. It is nullable for now because no login exists. From Phase 1 it will become NOT NULL in new tables.

```java
@Version
@Column(nullable = false)
private long version;
```
The optimistic-lock counter (see Q&A). Hibernate manages it. You never set it.

```java
public UUID getId() { return id; }
public Instant getCreatedAt() { return createdAt; }
public Instant getUpdatedAt() { return updatedAt; }
public UUID getCreatedBy() { return createdBy; }
public long getVersion() { return version; }
```
Read-only access. There are no setters on purpose (Rule 5 and the code rules: entities are changed only by named methods).

### 2.3 `JpaAuditingConfig.java` — line by line

Location: `Nexora-backend/src/main/java/com/nexora/shared/config/JpaAuditingConfig.java`

```java
@Configuration
```
A class that defines beans. Spring finds it when it scans `com.nexora`.

```java
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
```
Switches the auditing machinery on. Without it, `AuditingEntityListener` exists but does nothing. `auditorAwareRef` is the **name of the bean** to ask "who is the user?". It must equal the `@Bean` method name below.

```java
public class JpaAuditingConfig {
```
Plain class. Nothing else is needed on it.

```java
@Bean
public AuditorAware<UUID> auditorProvider() {
    return () -> Optional.empty();
}
```
- `@Bean`: the returned object becomes a Spring bean named `auditorProvider` (the method name).
- `AuditorAware<UUID>` is an interface with one method, `getCurrentAuditor()`, returning `Optional<UUID>`. Because it has one method, a lambda can implement it.
- `() -> Optional.empty()` means "there is no current user yet". So `createdBy` stays null.
- In task 1.4 this body will read the authenticated user id from Spring Security.

### 2.4 How it all works at runtime (what happens on `save()`)
1. Your code creates a `Party` (which extends `AuditableEntity`) and calls `repository.save(party)`.
2. Hibernate generates the UUID and is about to INSERT.
3. `AuditingEntityListener` runs first: sets `createdAt` and `updatedAt` = now, and asks `auditorProvider` for `createdBy`.
4. Hibernate inserts the row with `version = 0`.
5. Later, on an update, the listener refreshes `updatedAt`, and Hibernate runs `UPDATE ... SET ..., version=1 WHERE id=? AND version=0`. If 0 rows are updated, someone else changed it first, so Hibernate throws an optimistic-lock exception.

### 2.5 Answers to the review questions
1. **Which package is each file in?**
   `AuditableEntity` → `com.nexora.shared.domain`. `JpaAuditingConfig` → `com.nexora.shared.config`. Both are in `shared` because every module depends on them. Putting them inside one module would make modules depend on each other.
2. **Does `AuditableEntity` have a setter anywhere?**
   No. State changes only through named methods on the child entities, which can enforce business rules. A `setStatus()` could skip the legal-transition check (Rule 8).
3. **Is it `Instant` everywhere?**
   Yes. `Instant` is a UTC point in time, matches `timestamptz`, and avoids zone bugs.
4. **Optimistic vs pessimistic locking in two sentences.**
   Optimistic: don't block anyone; each row carries a `version`, and on save Hibernate checks it is unchanged, failing if someone else modified the row first. Good when conflicts are rare.
   Pessimistic: lock the row up front with `SELECT ... FOR UPDATE`, so others wait. Used where correctness is critical, such as stock and cash (Rule 3), from Phase 2.
5. **Why `Instant` and not `LocalDateTime`?** See above: `LocalDateTime` carries no time zone.
6. **Why `updatable = false` on `createdAt`?** The creation time must never change after insert.
7. **Why is the class `abstract`?** A bare `AuditableEntity` has no table and no meaning. Only subclasses are real entities.
8. **Why no Lombok / `@Data`?** `@Data` generates setters and `equals/hashCode` on every field, which breaks entity rules (no setters; identity-based equality).
9. **Why must the migration have these columns?** `ddl-auto=validate` makes Hibernate check, at startup, that the table has `created_at`, `updated_at`, `created_by` and `version`. A missing column fails startup. This is why task 1.9 adds `version`, `created_by`, and `NOT NULL` to V3.

### 2.6 Interview lines
- **Optimistic locking:** "Each row has a version. The update says `WHERE id=? AND version=?`. If it affects zero rows, someone else got there first and we report a conflict instead of overwriting."
- **Why a base class?** "Cross-cutting columns live in one `@MappedSuperclass`, so every table is consistent and nobody can forget to set timestamps."
- **Why UUIDs?** "No coordination needed, safe to expose in APIs, and ids can be created before insert."

---

## Part 3 — Questions I asked that you still need to answer in your own words
Fill these in after writing the code (writing them yourself is what makes them stick):
1. What does `@MappedSuperclass` do, and what would happen without it?
2. What two things are needed for `@CreatedDate` to work?
3. Why does `auditorAwareRef` have to match a bean name?
4. What happens, step by step, when two users update the same row at the same time?
5. Why does an entity have no setters?

---

## Part 4 — Review of my 0.4 code and the lessons from it

### Bug found: `@LastModifiedBy` on `updatedAt`
- `@LastModifiedDate` = fills a **timestamp** when the row is saved/updated.
- `@LastModifiedBy` = fills a **user** from `AuditorAware` (type `UUID`).
- Putting `@LastModifiedBy` on an `Instant` field asks Spring to put a UUID user id (or nothing, since there is no user yet) into a timestamp. `updated_at` would stay null and break the NOT NULL column.
- Rule to remember: **Date → timestamp annotations, By → user annotations.** The pairs are `@CreatedDate/@LastModifiedDate` and `@CreatedBy/@LastModifiedBy`.

### Good decision: `@jakarta.persistence.Version`
`org.springframework.data.annotation.*` and `jakarta.persistence.*` **both** contain an `@Version` annotation (and an `@Id`). With two wildcard imports Java does not know which one you mean (compile error "reference is ambiguous"). Hibernate only understands the **jakarta** one for optimistic locking, so spelling out `jakarta.persistence.Version` (or importing it explicitly) is correct. Same for `Id`.
Lesson: when two wildcard imports clash, import the one you need explicitly or use its full name.

### Answers check
- Packages: `shared.domain` and `shared.config`. Correct.
- Setters: none. Correct.
- "Is it Instant everywhere?": Instant only for the date/time fields, UUID for the ids, `long` for version. That is the right answer (my question was loosely worded).
- Optimistic vs pessimistic, refined:
  - Optimistic: nobody waits. Everyone reads and works freely; at save time Hibernate checks the version, and the loser gets an exception and must retry.
  - Pessimistic: the row is locked when it is **read** (`SELECT ... FOR UPDATE`). Anyone else who wants the same row **waits** until the first transaction commits or rolls back. Nobody gets a conflict error, but they queue.
  - Short version: optimistic = detect conflict afterwards, pessimistic = prevent conflict beforehand by blocking.

### Style notes
- Put a comment on its own line, not after `package ...;`.
- Keep indentation consistent (the last two getters were not indented).

---

## Part 5 — Task 0.5: error handling (ErrorCode, BusinessException, GlobalExceptionHandler)

### Concepts
- **Exception:** an object signalling a failure. `throw` jumps out of the current method and up the call stack until something catches it.
- **Checked vs unchecked:** checked exceptions (`extends Exception`) must be caught or declared; unchecked (`extends RuntimeException`) need not. Spring's `@Transactional` rolls back on **unchecked** exceptions (and Errors) by default, not on checked ones.
- **Enum with data:** `ErrorCode.INSUFFICIENT_INVENTORY(HttpStatus.CONFLICT)`. The constant carries its HTTP status, so code and status can never drift apart. An enum constructor is implicitly private.
- **`@RestControllerAdvice`:** one class holding `@ExceptionHandler` methods for all controllers. Spring picks the **most specific** handler for the exception thrown.
- **`ProblemDetail` (RFC 9457):** Spring's standard error body (`type, title, status, detail, instance`) plus our own properties (`code`, `timestamp`). Content type `application/problem+json`.
- **`ResponseEntityExceptionHandler`:** Spring's base class that already maps ~15 MVC exceptions (404, 405, bad JSON, ...) to ProblemDetail. Without extending it, a catch-all `Exception.class` handler turns those into 500s.
- **409 vs 422:** 409 = conflicts with the *current state*, might succeed later (stock arrives). 422 = well-formed but never valid as asked (vuda warp into production).
- **DRY:** the three handlers share one `build(ErrorCode, detail, request)` helper.

### Lessons from the bugs found
1. A stale `bootRun` still holding port 8080 made new code look broken. If a change seems to have no effect: `ss -ltnp | grep 8080`.
2. A misspelled enum constant (`CONCURRENT_MODIFICATON`) compiles fine but becomes a permanent API contract. Names that clients match on need extra care.
3. Hard-coded strings that duplicate an enum drift silently. Use the enum.
4. Known gap: Spring's own 404/405 bodies do not yet include `code` and `timestamp` (override `handleExceptionInternal` to add them).

### Review answers (with corrections)
1. **Why does `BusinessException` extend `RuntimeException`?** Spring rolls a transaction back only for unchecked exceptions by default. A business rule violation must undo the whole use case (Rule 4), so it is unchecked. Also, callers are not forced to write try/catch everywhere. ✔
2. **What does `@RestControllerAdvice` do?** Handles exceptions for all controllers in one place, so controllers need no try/catch and every error has the same shape. ✔
3. **409 or 422 for "stock would go negative"?** 409. The conflict depends on the *current state*; the same request could succeed after more stock is received. (422 is for requests that can never be valid.)
4. **Why must the catch-all handler not return `ex.getMessage()`?** CORRECTION: the reason is **security**. Exception messages can contain SQL, table and column names, file paths or class names, which help an attacker. So the client gets a generic message, and the full exception is **logged server-side** with `log.error(..., ex)`. (Finding where the error happened is the job of the log, not the response.)
5. **What does `code` give the Android app that the HTTP status does not?** The status is coarse: many different errors share 409. The `code` lets the app tell `INSUFFICIENT_INVENTORY` from `ORDER_LOCKED` and show a specific (even translated) message. The code is stable; the human message may be reworded.

### Interview lines
- "Business rule violations are unchecked exceptions so Spring rolls the transaction back; one `@RestControllerAdvice` maps them to RFC 9457 problem responses with a stable error code."
- "We never leak exception messages to clients; we log them and return a generic 500."

---

## Part 6 — Task 0.6: Clock, PageResponse, Money/Weight

- **Inject a `Clock`, don't call `Instant.now()`:** one time source and zone for the whole app; tests swap in `Clock.fixed(...)`. DB timestamps stay UTC; the Indian zone is only for business dates.
- **`java.time.Clock` vs `io.micrometer...Clock`:** same simple name, different packages. Check the import when the IDE offers several.
- **`BigDecimal.equals` compares scale** (`2.0` ≠ `2.00`); use `compareTo` for numbers. Build values from Strings, never from doubles.
- **Scale and rounding:** money scale 2, weight scale 3, `HALF_UP` (a tie goes up). Whole-rupee billing is still an open owner question (Q5).
- **`record`:** immutable data class with generated constructor, accessors, equals, hashCode, toString. `PageResponse<T>` is generic so one type serves every list endpoint.
- **Utility class pattern:** `final` class + private constructor + static methods (`Money`, `Weight`).
- **Constructor injection pays off in tests:** `new GlobalExceptionHandler(Clock.fixed(...))` needs no Spring context.

Review answers (model answers, since these were skipped):
1. A `Clock` makes time controllable in tests and consistent across the app.
2. `equals` also compares scale; use `compareTo`.
3. A record gives an immutable data carrier with no boilerplate; ideal for DTOs.
4. `final` + private constructor stops anyone extending or instantiating a class that only has static methods.
5. Bill rounding (whole rupees or paise) is a business decision for the owner (Q5), not a technical one.

---

## Part 7 — Task 0.7: BusinessNumberGenerator (`PUR-2026-0001`)

### The problem
Two requests creating a purchase at the same moment must never receive the same number, and a failed request must not leave a gap (ADR-007).
Rejected options: `MAX()+1` (race condition), native Postgres sequences (gaps on rollback, no yearly reset).

### The design
1. Table `business_number_sequences(prefix, seq_year, next_value)`, one row per prefix and year (migration `V4`).
2. `INSERT ... ON CONFLICT DO NOTHING` creates the row for a new year if it does not exist (safe when two requests race).
3. `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`) locks that row. Others wait.
4. Take `next_value`, add 1, return `PREFIX-YEAR-0000` formatted. The new value is written at commit.
5. The lock is released when the caller's transaction ends, so a rollback also undoes the increment: **gap-free**.

### New concepts
- **Pessimistic lock:** `FOR UPDATE` blocks other transactions that want the same row until commit/rollback.
- **`@Transactional(propagation = MANDATORY)`:** the method must run inside an existing transaction, otherwise Spring throws. Needed here because the lock only lives as long as the transaction.
- **`@Modifying` + `nativeQuery`:** a data-changing query written in plain SQL (`ON CONFLICT` is PostgreSQL syntax, not JPQL).
- **Package-private repository:** only `BusinessNumberGenerator` in the same package can use it; other modules go through the service.
- **Testcontainers:** a real PostgreSQL in a throwaway Docker container; `@DynamicPropertySource` points `spring.datasource.*` at it; the "singleton container" pattern shares one container across test classes.
- **`CountDownLatch`:** a starting gun so all threads begin at the same instant, which makes the race real.
- **Mutation check:** disabling `@Lock` made the concurrency test fail (with `ObjectOptimisticLockingFailureException`, because `@Version` acted as a safety net). A test that cannot fail proves nothing.

### Interview lines
- "Business numbers come from a counter row locked with `SELECT FOR UPDATE` inside the use-case transaction, so they are unique under concurrency and gap-free on rollback. A Postgres sequence would leave gaps."
- "Optimistic locking detects the conflict after the fact; here contention is high and correctness critical, so I lock pessimistically."
