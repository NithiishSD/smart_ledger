# SmartSilk Domain Rules — Implementation Reference

> Condensed, implementation-ready catalogue of the business rules designed in
> `smart-ledger-docs/` (Steps 4–8). When this file and a design doc disagree, **this file
> wins** (it records the reconciled decision). Changing a rule here = a requirement change:
> note it in the Change Log at the bottom.

---

## 1. Glossary

| Term | Meaning |
|---|---|
| **Raw silk** | Silk yarn bought from suppliers. Measured in **kg only**. |
| **Warp** | Processed silk (rolled + warped) sold to customers. Types: `SILK_WARP`, `KORA_WARP`. |
| **Vuda warp** | `VUDA_WARP` — bought and resold (direct trading). Kept in `RAW_STOCK` as trading stock. **Never** enters production or outsourcing. |
| **Rolling / Warping** | Internal production steps. Rolling is paid **per hour**, warping **per kg**. |
| **Internal WIP** | Raw silk consumed into an in-house production batch, not yet finished. |
| **External WIP** | Raw silk issued to an outside manufacturer, not yet returned as warp. |
| **Party** | Any external person/business: customer, supplier, manufacturer, worker, lender. One party may hold several roles (`CUSTOMER`, `SUPPLIER`, `MANUFACTURER`, `WORKER`, `LENDER`). |
| **User** | Someone who logs into the app. A user is **not** a party. |
| **Chit** | Chit-fund investment. Cash-only. Contributions are not expenses, payouts are not revenue. |
| **Personal drawing** | Money the owner takes for personal use; excluded from business profit. |
| **Allocation** | How one payment is split across obligations (orders, purchases, jobs, work records). |
| **Business number** | Human ID like `PUR-2026-0012`. Internal ID is always a UUID. |

## 2. The eight non-negotiable rules (domain freeze rules)

1. Don't add an entity unless it has its own identity, lifecycle, or obligation.
2. **Never store derived values** (outstanding, stock, balances) as editable fields. The only allowed projection is `inventory_balances`, and it is updated in the same transaction as its movement.
3. Business events (Payment, Expense) and money movements (FinancialTransaction) are separate records.
4. A module never writes another module's tables directly. It calls that module's service.
5. **Every inventory change creates a `material_movement`.**
6. **Every money change creates a `financial_transaction`.**
7. Every settlement keeps its allocation history.
8. Historical rates, prices and quantities are never recalculated from current values.

Corollaries:
- Financial and inventory records are **never updated in place or hard-deleted** after they take effect. Fix mistakes with a reversal or correction record.
- The **backend is the authority.** The client sends intent ("receive 48.5 kg"), never results ("stock is now 500").
- No generic `POST /inventory/movements` or `POST /financial-transactions` endpoint for clients.

## 3. Product types & inventory locations

`ProductType`: `RAW_SILK`, `SILK_WARP`, `KORA_WARP`, `VUDA_WARP`

`StockLocation`: `SUPPLIER` (outside, source), `RAW_STOCK`, `INTERNAL_WIP`, `EXTERNAL_WIP`,
`FINISHED_STOCK`, `CUSTOMER` (outside, sink), `WASTAGE` (sink), `RETURNED` (sink)

Balances are tracked for internal locations only: `RAW_STOCK`, `INTERNAL_WIP`, `EXTERNAL_WIP`, `FINISHED_STOCK`.

Which products each internal location holds: `RAW_STOCK` = raw silk and vuda warp (trading stock); `INTERNAL_WIP`, `EXTERNAL_WIP` and
`FINISHED_STOCK` = silk warp and kora warp. Work in progress is tracked as the warp type being made: a movement records the product leaving
(`product_type`) and the product arriving (`to_product_type`); they differ only for `CONSUMPTION` and `OUTSOURCE_ISSUE` (raw silk in, target warp type recorded).

### Movement types (from → to)

| MovementType | From → To | Created by |
|---|---|---|
| `OPENING_BALANCE` | — → any internal | Go-live data import |
| `RECEIPT` | SUPPLIER → RAW_STOCK (accepted kg only; raw silk or vuda warp) | Procurement: receive material |
| `PURCHASE_RETURN` | RAW_STOCK → RETURNED | Procurement: return to supplier |
| `DIRECT_SALE` | RAW_STOCK → CUSTOMER (raw silk and vuda warp only) | Orders: deliver raw silk / vuda |
| `CONSUMPTION` | RAW_STOCK → INTERNAL_WIP (raw silk arrives as the batch's warp type) | Production: start batch |
| `PRODUCTION_OUTPUT` | INTERNAL_WIP → FINISHED_STOCK | Production: complete batch |
| `WASTAGE` | INTERNAL_WIP / EXTERNAL_WIP / RAW_STOCK → WASTAGE | Production / outsourcing / manual |
| `OUTSOURCE_ISSUE` | RAW_STOCK → EXTERNAL_WIP (raw silk arrives as the job's warp type) | Outsourcing: issue |
| `OUTSOURCE_RECEIPT` | EXTERNAL_WIP → FINISHED_STOCK | Outsourcing: receive |
| `DELIVERY` | FINISHED_STOCK → CUSTOMER | Orders: deliver |
| `CUSTOMER_RETURN` | CUSTOMER → FINISHED_STOCK (warp) or RAW_STOCK (raw silk / vuda) | Orders: return |
| `ADJUSTMENT` | any ↔ any, **reason required**, `INVENTORY_ADJUST` permission | Inventory: correction |

Quantities are always **positive**. Direction comes from from/to.

## 4. Invariants by module

### Parties
- `name` required. Phone is **not** unique (family members share numbers).
- A party must have the right role for the operation: purchases need `SUPPLIER`, orders need `CUSTOMER`, outsourcing needs `MANUFACTURER`, work records need `WORKER`, loans need `LENDER` → otherwise `INVALID_PARTY_ROLE`.
- An `INACTIVE` party cannot be used in new transactions (`PARTY_INACTIVE`). Their history stays visible.
- Parties are never hard-deleted once referenced.

### Procurement
- `ordered_weight_kg > 0`, `rate_per_kg >= 0`, `credit_days >= 0`.
- `due_date = purchase_date + credit_days`, computed by the backend.
- Per receipt: `accepted + rejected <= received`, and **only accepted kg enters `RAW_STOCK`**.
- Σ received across receipts `<= ordered_weight_kg` → otherwise `PURCHASE_RECEIPT_EXCEEDED`.
- A return cannot exceed the kg still available from that purchase, and never makes stock negative. The original receipt is never rewritten.
- Purchase price is historical and never recalculated.

### Inventory
- Movement quantity `> 0`.
- **Stock never goes negative** (`INSUFFICIENT_INVENTORY`), with no exception: an `ADJUSTMENT` needs the `INVENTORY_ADJUST` permission and a reason, and it cannot take a balance below zero either (ADR-026).
- Check-and-decrement is atomic. Lock the `inventory_balances` row with `PESSIMISTIC_WRITE` before checking.
- `VUDA_WARP` cannot be consumed, issued, or produced (`INVALID_PRODUCT_FOR_OPERATION`).

### Orders
- An order needs a customer and ≥1 item. Each item has `required_weight_kg > 0` and `rate_per_kg >= 0`.
- `amount = required_weight_kg × rate_per_kg`, computed by the backend and rounded HALF_UP to 2dp.
- Once production or outsourcing starts for any item, the order is **locked**: no edit or cancel (`ORDER_LOCKED`). Use `order.canModify()`, never a raw boolean check.
- No partial delivery of a single warp item (business rule 12).
- Operational status and payment status are **separate** fields/concepts.

### Production
- `input > 0`, `output >= 0`, `wastage >= 0`.
- On completion: **`input = output + wastage + discrepancy`**. Discrepancy must be explicit and visible, never silent → otherwise `PRODUCTION_NOT_RECONCILED`.
- Start = CONSUMPTION movement. Complete = PRODUCTION_OUTPUT + WASTAGE movements. Each runs in **one transaction**.
- A batch can serve many order items and an order item can be served by many batches (`production_order_items`).

### Outsourcing
- Needs a `MANUFACTURER`. Issued kg `> 0`.
- Received + wastage/difference `<=` issued (`OUTSOURCE_RECEIPT_EXCEEDED`).
- Linking an order is **optional** (the business may outsource ahead of demand).
- Manufacturer payable = received kg × job `rate_per_kg` (historical rate).

### Workforce
- `ROLLING` requires `hours`. `WARPING` requires `quantity_kg`.
- `amount` = hours or kg × `rate`. The rate is **copied into the record** and never looked up later.
- Work done ≠ money paid. Payable = Σ amount − Σ allocations.

### Payments & allocation
- `amount > 0`. The party must exist and be active. The account must exist.
- **Oldest-first allocation** (by obligation date, then business number) unless an explicit allocation is supplied. Explicit allocations are still validated: owned by this party, still outstanding, each allocation `<=` outstanding (`PAYMENT_ALLOCATION_EXCEEDED`), Σ allocations `<=` payment amount.
- Customer excess (unallocated remainder) is an **advance**, not revenue. It stays visible and is never silently dropped.
- Payment + allocations + financial transaction commit **atomically**.
- Retry-sensitive `POST`s (payments, expenses, transfers, receipts) accept an `Idempotency-Key` header. A replay returns the stored original response (`DUPLICATE_REQUEST` if the same key arrives with a different body).
- Corrections use **reversal** (status `REVERSED` + compensating financial transaction). Never `PUT` the amount.

### Finance
- Account types: `CASH`, `BANK`. Each bank account is its own row. Balances are always derived.
- Opening balances are `OPENING_BALANCE` transactions, **not** a column.
- Transfer: source ≠ destination. It creates one OUT + one IN transaction and is **not** revenue or expense. Lock both accounts in **ascending UUID order** to avoid deadlocks.
- **Cash and bank accounts cannot go negative** (`INSUFFICIENT_FUNDS`; no overdraft in the MVP, ADR-031, assumption Q4).
- Manual account correction (`ADJUSTMENT` transaction, `IN` or `OUT`): needs `FINANCE_MANAGE` and a reason, never makes the account negative, and is audited (`ACCOUNT_ADJUSTED`). It is the only way to fix a counted-cash or bank difference; recorded transactions are still corrected by reversal.
- Payments to a chit organiser are `CHIT_ORGANISER_PAYMENT` (`OUT`, linked to the chit, cash only).
- Chit transactions: payment method must be `CASH` (`INVALID_PAYMENT_METHOD`).
- Loan received = liability (not revenue). Principal repayment reduces the liability (not an expense). Interest = expense.
- Revenue ≠ every inflow. Loans, transfers and chit payouts are not sales revenue.
- Personal drawings are recorded but excluded from business profit.

### Integrations
- No external call (WhatsApp, AI, email, file storage) runs inside a DB transaction. Commit first, then do the side effect async with retry.
- Voice/AI never mutates data directly: intent → structured command → validation → **user confirmation** → the normal application service.

## 5. State machines

Transitions happen through **named methods on the entity** (`purchase.recordReceipt()`, `order.cancel()`), never a status setter. An illegal transition throws `INVALID_STATE_TRANSITION` (409).

```
Purchase:        ORDERED ──receive──▶ PARTIALLY_RECEIVED ──receive──▶ FULLY_RECEIVED
                 ORDERED ──cancel (nothing received)──▶ CANCELLED

CustomerOrder:   PLACED ─▶ CONFIRMED ─▶ IN_PROGRESS ─▶ READY ─▶ DELIVERED ─▶ COMPLETED (set explicitly)
                 CONFIRMED ──ready (filled from existing stock)──▶ READY
                 PLACED | CONFIRMED ──cancel──▶ CANCELLED      (locked from IN_PROGRESS on; not while payment allocations exist)
OrderPayment:    UNPAID ─▶ PARTIALLY_PAID ─▶ PAID  (│ OVERPAID)  — derived, not user-set

ProductionBatch: PLANNED ──start──▶ IN_PROGRESS ──complete──▶ COMPLETED
                 PLANNED ──cancel──▶ CANCELLED

OutsourcingJob:  CREATED ──issue──▶ MATERIAL_ISSUED ──receive──▶ PARTIALLY_RECEIVED ──receive──▶ COMPLETED
                 CREATED ──cancel──▶ CANCELLED

Party:           ACTIVE ⇄ INACTIVE
Payment/Expense/WorkRecord: RECORDED ──reverse──▶ REVERSED   (reversal writes the compensating record)
WorkRecord payment: UNPAID ─▶ PARTIALLY_PAID ─▶ PAID   — derived from allocations
Loan:            ACTIVE ─▶ CLOSED        Chit: ACTIVE ─▶ MATURED ─▶ CLOSED, ACTIVE ─▶ CLOSED (MATURED ─▶ ACTIVE only by reversing the payout)
```

## 6. Derived values (computed, never stored)

| Value | Formula |
|---|---|
| Stock(product, location) | Σ movements into location − Σ movements out. `inventory_balances` caches it and can be rebuilt from movements. |
| Account balance | Σ IN − Σ OUT over `financial_transactions` for the account |
| Total available money | Σ CASH balances + Σ BANK balances |
| Supplier payable | Σ purchase value − Σ supplier allocations − Σ return credit ⚠ see Q1 |
| Customer outstanding | Σ order item amounts (per Q2) − Σ customer allocations |
| Manufacturer payable | Σ (job received kg × job rate) − Σ manufacturer allocations |
| Worker payable | Σ work record amounts − Σ worker allocations |
| Loan outstanding | principal − Σ `LOAN_PRINCIPAL_REPAYMENT` |
| External WIP | balance of `EXTERNAL_WIP` (also per job: issued − received − wastage) |

Reports and the dashboard compute these with **SQL aggregation** (GROUP BY / DTO projections), never with N+1 loops.

## 7. Error codes (stable API contract)

The `code` field is machine-readable and **never changes**. The message can be reworded.

| Code | HTTP | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Bean Validation failed (field errors listed) |
| `MALFORMED_REQUEST` | 400 | Unparseable JSON / wrong types / unknown enum value |
| `METHOD_NOT_ALLOWED` | 405 | HTTP method not supported on the path |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Content type not supported |
| `UNAUTHENTICATED` / `TOKEN_EXPIRED` / `INVALID_CREDENTIALS` | 401 | Auth problems |
| `ACCESS_DENIED` | 403 | Missing permission |
| `RESOURCE_NOT_FOUND` | 404 | Unknown id / business number |
| `DUPLICATE_BUSINESS_NUMBER` | 409 | Unique constraint on a business number |
| `DUPLICATE_REQUEST` | 409 | Idempotency key reused with a different payload |
| `DUPLICATE_RESOURCE` | 409 | Unique constraint violated (other than a business number) |
| `CONCURRENT_MODIFICATION` | 409 | Optimistic lock (`@Version`) conflict, or a row lock not obtained within 5 s |
| `INVALID_STATE_TRANSITION` | 409 | Illegal status change |
| `ORDER_LOCKED` | 409 | Edit/cancel after production started |
| `INSUFFICIENT_INVENTORY` | 409 | Stock would go negative |
| `INSUFFICIENT_FUNDS` | 409 | Cash would go negative |
| `PURCHASE_RECEIPT_EXCEEDED` | 409 | Receiving more than ordered |
| `OUTSOURCE_RECEIPT_EXCEEDED` | 409 | Receiving more than issued |
| `PAYMENT_ALLOCATION_EXCEEDED` | 409 | Allocation > outstanding or > payment |
| `INVALID_PAYMENT_ALLOCATION` | 422 | Allocation targets another party's obligation, etc. |
| `PRODUCTION_NOT_RECONCILED` | 422 | input ≠ output + wastage + discrepancy |
| `INVALID_PARTY_ROLE` / `PARTY_INACTIVE` | 422 | Wrong or inactive party |
| `INVALID_PRODUCT_FOR_OPERATION` | 422 | e.g. vuda warp into production |
| `INVALID_PAYMENT_METHOD` | 422 | e.g. non-cash chit transaction |
| `RATE_LIMITED` | 429 | Request rate limit exceeded (`Retry-After` header) |
| `INTERNAL_ERROR` | 500 | Unexpected. Log it with the correlation id and never leak the stack trace. |

**Convention:** 409 = conflicts with the *current state* (it could succeed later or with other data). 422 = the request is well-formed but semantically invalid regardless of state.

## 8. Open business questions (ask the owner before implementing the affected phase)

| # | Question | Blocks | MVP assumption (confirm with owner) |
|---|---|---|---|
| Q1 | Is supplier payable based on **ordered** kg × rate, or **accepted** kg × rate? How do returns reduce it? | Phase 3 supplier payments | Payable = accepted kg × purchase rate; a supplier return reduces it by returned kg × the purchase rate |
| Q2 | When does a customer obligation start: at order **confirmation** or at **delivery**? | Phase 3 outstanding | At **confirmation**: items of `CONFIRMED`..`COMPLETED` orders are owed; earlier payments are an advance; an order with allocations cannot be cancelled |
| Q3 | Supplier overdue penalty: per-day rate? fixed? per supplier? (must be configurable) | Phase 6 reminders | No penalty amount; reminders `days_before_due` (default 3), on the due date, then every `overdue_repeat_days` (default 1) |
| Q4 | Can a bank account go negative (overdraft)? | Phase 5 | No overdraft: cash and bank accounts never go negative (ADR-031) |
| Q5 | Rounding: are ₹ amounts rounded to whole rupees on bills, or kept to paise? | Phase 3 | Keep paise: item amount rounded HALF_UP to 2 dp; totals are sums of rounded items |
| Q6 | Chit bidding/payout calculation (`CHIT_BIDDING_CALCULATION` is still undefined) | Phase 5 chits | Contribution and payout amounts entered by the user; no bidding calculation |
| Q7 | Does direct raw-silk sale go through a customer order (recommended) or a separate sale? | Phase 3 | Through a normal customer order (items `RAW_SILK` / `VUDA_WARP`); delivery creates `DIRECT_SALE` |
| Q8 | Who else will log in at go-live (OWNER only per NFR 20.3, or STAFF too)? | Phase 1 seed data | OWNER only; `STAFF` role seeded read-only; no user-management endpoint in the MVP |

## Change Log
| Date | Change | Reason |
|---|---|---|
| 2026-09-29 | Initial consolidation from design docs Steps 4–8 | Start of implementation phase |
| 2026-10-08 | Party role `LENDER` added; loans link to a `LENDER` party | Loans need a party with contact details (exact-schema design) |
| 2026-10-08 | Vuda warp is purchased and kept in `RAW_STOCK`; `DIRECT_SALE` only from `RAW_STOCK` | Vuda is bought and resold; matches `RECEIPT` → `RAW_STOCK` |
| 2026-10-08 | Movements carry `to_product_type`; WIP holds the warp type being made | Raw silk becomes warp inside work in progress |
| 2026-10-08 | Stock adjustments can never make stock negative (ADR-026) | Physical stock cannot be below zero |
| 2026-10-08 | Order path: `CONFIRMED → READY` allowed; `COMPLETED` set explicitly; cancel blocked while allocations exist | Deliver from existing stock; payments must be reversed first |
| 2026-10-08 | Work records and expenses: `RECORDED → REVERSED` | Corrections without deleting history |
| 2026-10-08 | Error codes added: `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`, `DUPLICATE_RESOURCE`, `RATE_LIMITED`; lock timeout → `CONCURRENT_MODIFICATION`; unknown enum → `MALFORMED_REQUEST` | Needed by the error handler and rate limiting |
| 2026-10-08 | MVP assumptions recorded for open questions Q1–Q8 (section 8) | Build can proceed; each must be confirmed with the owner before go-live |
| 2026-10-08 | Finance: bank accounts also cannot go negative (Q4); manual `ADJUSTMENT` transaction with reason + `FINANCE_MANAGE` + audit; `CHIT_ORGANISER_PAYMENT` type | FR-FIN-15 and FR-CHIT-04 require them; no overdraft per ADR-031 |
