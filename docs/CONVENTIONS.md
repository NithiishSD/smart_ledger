# Coding Conventions & Reference Patterns

How code in `Nexora-backend` is written. The AI assistant follows this file, and so should you.
The snippets are **patterns taken from a neutral example domain (library books)**, so you learn the
shape without being handed the SmartSilk solution.

---

## 1. Package layout

```
com.nexora
├── NexoraApplication.java
├── shared/                         # cross-cutting, no business rules
│   ├── api/        ApiError codes, PageResponse, GlobalExceptionHandler
│   ├── domain/     BaseEntity/AuditableEntity, Money/Weight helpers, BusinessException hierarchy
│   ├── numbering/  BusinessNumberGenerator (+ sequences table)
│   ├── security/   SecurityConfig, JWT, CurrentUser, Permission enum
│   ├── idempotency/
│   └── config/     Clock, OpenAPI, Jackson
├── identity/       users, roles, permissions, auth
├── parties/
├── procurement/    purchases, material receipts, supplier returns
├── inventory/      material movements, inventory balances (NO public write endpoint)
├── orders/         customer orders, order items, delivery
├── production/
├── outsourcing/
├── workforce/      work records
├── finance/        accounts, financial transactions, payments, allocations, expenses, transfers
├── loans/  chits/
├── documents/  notifications/
└── reporting/      dashboard + reports (read-only SQL projections)
```
Inside each module:
```
<module>/
├── api/             XController, dto/ (XRequest, XResponse records)
├── application/     XService (use cases, @Transactional), mappers
├── domain/          entities, enums, value objects, domain exceptions
└── infrastructure/  XRepository (Spring Data), native queries, adapters
```
**Cross-module rule:** `procurement` calls `inventory` through `InventoryService.recordMovement(...)`,
**never** through `MaterialMovementRepository`. Entities of other modules are referenced by
**UUID**, not by a JPA relation (`UUID supplierId`, not `@ManyToOne Party supplier`). Inside one
module, `@ManyToOne` is fine (e.g. `OrderItem → CustomerOrder`).

## 2. Naming

| Thing | Convention | Example |
|---|---|---|
| Packages | lowercase, no underscores | `com.nexora.procurement.api` |
| Tables / columns | `snake_case`, plural tables | `material_receipts.received_weight_kg` |
| FK columns | `<entity>_id` | `supplier_id` |
| Java fields | camelCase with unit suffix | `receivedWeightKg`, `ratePerKg` |
| Request DTO | `<Verb><Noun>Request` | `CreatePurchaseRequest`, `RecordReceiptRequest` |
| Response DTO | `<Noun>Response` / `<Noun>SummaryResponse` | `PurchaseResponse` |
| Use-case service | `<Aggregate>Service` with verb methods | `PurchaseService.receiveMaterial(...)` |
| Exception | `<Condition>Exception` + an `ErrorCode` | `PurchaseReceiptExceededException` |
| Migration | `V<n>__<verb>_<what>.sql` | `V5__create_procurement_tables.sql` |
| Branch | `feature/<area>-<short>` · `fix/...` · `chore/...` | `feature/procurement-receipts` |
| Commit | Conventional Commits | `feat(procurement): record material receipt` |

## 3. Entity pattern (rich entity, no setters)

```java
@Entity
@Table(name = "books")
public class Book extends AuditableEntity {          // id, createdAt, updatedAt, createdBy, version

    @Column(name = "book_number", nullable = false, unique = true, updatable = false)
    private String bookNumber;

    @Column(name = "copies_total", nullable = false)
    private int copiesTotal;

    @Column(name = "copies_lent", nullable = false)
    private int copiesLent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookStatus status;

    protected Book() { }                              // for JPA only

    public static Book register(String bookNumber, int copies) {   // factory enforces invariants
        if (copies <= 0) throw new IllegalArgumentException("copies must be > 0");
        Book b = new Book();
        b.bookNumber = bookNumber;
        b.copiesTotal = copies;
        b.status = BookStatus.AVAILABLE;
        return b;
    }

    public void lend(int count) {                      // intention-revealing, guards state
        if (status == BookStatus.WITHDRAWN) throw new InvalidStateTransitionException(...);
        if (copiesLent + count > copiesTotal) throw new NotEnoughCopiesException(...);
        copiesLent += count;
    }
    // getters only
}
```
Rules:
- IDs use `@Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;` (in the base class).
- Enums use `@Enumerated(EnumType.STRING)`, and the migration adds a matching `CHECK (col IN (...))`.
- Money/weight use `BigDecimal` with `@Column(precision = 14, scale = 2)` / `(12, 3)`.
- `@ManyToOne(fetch = FetchType.LAZY)` always. Avoid bidirectional `@OneToMany` unless the parent truly owns the children (Order → OrderItems: `cascade = ALL, orphanRemoval = true`).
- Never `CascadeType.REMOVE` toward shared entities (party, account).
- No Lombok `@Data`/`@Setter` on entities. (Lombok is optional overall. Records cover DTOs.)
- `equals/hashCode`: based on `id` only, null-safe, or leave the defaults.

## 4. API pattern

```java
@RestController
@RequestMapping("/api/v1/books")
class BookController {
    private final BookService bookService;
    BookController(BookService bookService) { this.bookService = bookService; }   // constructor injection

    @PostMapping
    @PreAuthorize("hasAuthority('BOOK_CREATE')")
    ResponseEntity<BookResponse> register(@Valid @RequestBody RegisterBookRequest request) {
        BookResponse created = bookService.register(request);
        return ResponseEntity.created(URI.create("/api/v1/books/" + created.id())).body(created);
    }

    @PostMapping("/{id}/loans")                       // business action as sub-resource
    @PreAuthorize("hasAuthority('BOOK_LEND')")
    ResponseEntity<LoanResponse> lend(@PathVariable UUID id, @Valid @RequestBody LendBookRequest request) { ... }

    @GetMapping
    PageResponse<BookSummaryResponse> list(@RequestParam(required = false) BookStatus status,
                                           @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable) { ... }
}

public record RegisterBookRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull @Positive Integer copies,
        @Size(max = 1000) String notes) { }
```
- Controllers stay **under ~15 lines per method**: validate → call one service method → map status.
- Status codes: `201` + `Location` for create, `200` for read and for actions returning a body, `204` for actions without one.
- Lists return `PageResponse<T>{content, page, size, totalElements, totalPages}`. Cap `size` at 100.
- Request DTOs contain **only client-provided fields**: no ids, business numbers, statuses, totals or timestamps.
- Response DTOs never expose entities, password hashes or internal version fields.
- Filter with query params (`?status=&supplierId=&from=&to=&search=`).

## 5. Application service pattern

```java
@Service
public class BookService {
    // repositories of THIS module + service interfaces of OTHER modules
    @Transactional
    public LoanResponse lend(UUID bookId, LendBookRequest req) {
        Book book = bookRepository.findByIdForUpdate(bookId)                 // lock if contended
                .orElseThrow(() -> new ResourceNotFoundException("Book", bookId));
        memberService.requireActiveMember(req.memberId());                    // other module via service
        book.lend(req.count());                                               // domain rule
        Loan loan = loanRepository.save(Loan.open(book, req.memberId(), clock));
        events.publishEvent(new BookLent(loan.getId()));                      // side effects AFTER_COMMIT
        return mapper.toResponse(loan);
    }

    @Transactional(readOnly = true)
    public BookResponse get(UUID id) { ... }
}
```
- One public method = one use case = one transaction.
- Load → validate/lock → mutate the entity via its methods → save ledger rows → publish event → map.
- No `try/catch` for business errors. Let exceptions reach the global handler.
- No HTTP or servlet types in services.

## 6. Repository pattern

```java
interface BookRepository extends JpaRepository<Book, UUID> {
    Optional<Book> findByBookNumber(String bookNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Book b where b.id = :id")
    Optional<Book> findByIdForUpdate(UUID id);

    @Query("select b from Book b join fetch b.shelf where b.status = :status")   // avoid N+1
    List<Book> findWithShelfByStatus(BookStatus status);
}
```
- Reports use **DTO projections** (interface or record constructor expressions) or native SQL with `GROUP BY`. Never loop-and-query.
- No business logic in repositories.

## 7. Errors

- Every business exception extends `BusinessException(ErrorCode code, String message)`.
- `ErrorCode` enum carries the HTTP status: `INSUFFICIENT_INVENTORY(CONFLICT)`, … (list in `DOMAIN_RULES.md §7`).
- A single `@RestControllerAdvice GlobalExceptionHandler extends ResponseEntityExceptionHandler` maps:
  `BusinessException` → its status. `MethodArgumentNotValidException` → 400 `VALIDATION_FAILED` + `errors[]`. `ObjectOptimisticLockingFailureException` → 409 `CONCURRENT_MODIFICATION`. `DataIntegrityViolationException` (unique) → 409 `DUPLICATE_BUSINESS_NUMBER`. `AccessDeniedException` → 403. Anything else → 500 `INTERNAL_ERROR` (logged with the request id, no stack trace in the body).
- Response body (RFC 9457 `ProblemDetail`):
```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "Only 42.500 kg of RAW_SILK available in RAW_STOCK.",
  "instance": "/api/v1/production-batches/…/start",
  "code": "INSUFFICIENT_INVENTORY", "timestamp": "2026-09-29T10:15:30Z" }
```

## 8. Migrations (Flyway)

- Location `src/main/resources/db/migration`. One module or feature per file.
- Every table gets: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `created_by UUID` (FK `users`) where a human acts, and `version BIGINT NOT NULL DEFAULT 0` on editable aggregates.
- Add `NOT NULL` whenever the fact is mandatory. Use `NULL` only for "not known yet", never fake values.
- CHECK constraints for positive amounts and weights and for enum values. UNIQUE on business numbers.
- **Index every FK column** (Postgres doesn't do it automatically) plus common filters (`status`, dates).
- Name constraints: `pk_`, `fk_<table>_<ref>`, `uq_<table>_<col>`, `ck_<table>_<rule>`, `ix_<table>_<cols>`.
- Merged migrations are immutable (ADR-016).

## 9. Tests

| What | Tool | Location |
|---|---|---|
| Entity invariants / state machine | plain JUnit + AssertJ | `src/test/.../<module>/domain/` |
| Use case end-to-end with DB | `@SpringBootTest` + Testcontainers Postgres | `.../<module>/application/` |
| Controller validation / status / security | `@WebMvcTest` + MockMvc | `.../<module>/api/` |
| Concurrency (stock, payments) | integration test with 2 threads + `CountDownLatch` | `.../inventory/` |

- Test names: `receive_whenExceedsOrderedWeight_rejectsWithPurchaseReceiptExceeded()`.
- Every business rule in `DOMAIN_RULES.md` has at least one test proving it rejects bad input.
- A shared `AbstractIntegrationTest` starts one reusable Postgres container.
- Use the **expected-behaviour tables** from the task (e.g. 250 ordered, 100 received, +80 → 180 received, 70 remaining; +100 → reject).

## 10. Git & review workflow

1. Branch from `main`: `feature/<area>-<short>`.
2. Small commits in Conventional Commits style. Never commit secrets, `build/` or `.gradle/`.
3. Before a PR: `./gradlew build` is green, new migrations apply on a fresh DB, and the task's checklist is done.
4. Open a PR to `main` and run `/review` (AI review) before merging. Squash-merge.
5. Tick the task in `docs/ROADMAP.md`.

## 11. Definition of Done (every task)

- [ ] Behaviour matches the task's expected-behaviour table, including rejections with the right `code`
- [ ] Domain rules live in entities/services, not controllers
- [ ] Transaction boundary on the use-case method. Locks where DOMAIN_RULES demands them
- [ ] Ledger rows (movement / financial transaction) written for any stock/money effect
- [ ] Migration has constraints + FK indexes, and `ddl-auto=validate` passes
- [ ] Endpoint protected by a permission, DTOs validated
- [ ] Unit + integration tests green, including at least one failure path
- [ ] OpenAPI shows the endpoint with its error responses
- [ ] No secrets or tokens in code or logs
