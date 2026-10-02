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
| **Vuda warp** | `VUDA_WARP` — bought and resold (direct trading). **Never** enters production or outsourcing. |
| **Rolling / Warping** | Internal production steps. Rolling is paid **per hour**, warping **per kg**. |
| **Internal WIP** | Raw silk consumed into an in-house production batch, not yet finished. |
| **External WIP** | Raw silk issued to an outside manufacturer, not yet returned as warp. |
| **Party** | Any external person/business: customer, supplier, manufacturer, worker. One party may hold several roles. |
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

### Movement types (from → to)

| MovementType | From → To | Created by |
|---|---|---|
| `OPENING_BALANCE` | — → any internal | Go-live data import |
| `RECEIPT` | SUPPLIER → RAW_STOCK (accepted kg only) | Procurement: receive material |
| `PURCHASE_RETURN` | RAW_STOCK → RETURNED | Procurement: return to supplier |
| `DIRECT_SALE` | RAW_STOCK / FINISHED_STOCK → CUSTOMER | Orders: deliver raw silk / vuda |
| `CONSUMPTION` | RAW_STOCK → INTERNAL_WIP | Production: start batch |
| `PRODUCTION_OUTPUT` | INTERNAL_WIP → FINISHED_STOCK | Production: complete batch |
| `WASTAGE` | INTERNAL_WIP / EXTERNAL_WIP / RAW_STOCK → WASTAGE | Production / outsourcing / manual |
| `OUTSOURCE_ISSUE` | RAW_STOCK → EXTERNAL_WIP | Outsourcing: issue |
| `OUTSOURCE_RECEIPT` | EXTERNAL_WIP → FINISHED_STOCK | Outsourcing: receive |
| `DELIVERY` | FINISHED_STOCK → CUSTOMER | Orders: deliver |
| `CUSTOMER_RETURN` | CUSTOMER → FINISHED_STOCK | Orders: return |
| `ADJUSTMENT` | any ↔ any, **reason required**, OWNER only | Inventory: correction |

Quantities are always **positive**. Direction comes from from/to.

## 4. Invariants by module

### Parties
- `name` required. Phone is **not** unique (family members share numbers).
- A party must have the right role for the operation: purchases need `SUPPLIER`, orders need `CUSTOMER`, outsourcing needs `MANUFACTURER`, work records need `WORKER` → otherwise `INVALID_PARTY_ROLE`.
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
- **Stock never goes negative** (`INSUFFICIENT_INVENTORY`). The only exception is an OWNER `ADJUSTMENT`.
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
- **Physical cash cannot go negative** (`INSUFFICIENT_FUNDS`).
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

CustomerOrder:   PLACED ─▶ CONFIRMED ─▶ IN_PROGRESS ─▶ READY ─▶ DELIVERED ─▶ COMPLETED
                 PLACED | CONFIRMED ──cancel──▶ CANCELLED      (locked from IN_PROGRESS on)
OrderPayment:    UNPAID ─▶ PARTIALLY_PAID ─▶ PAID  (│ OVERPAID)  — derived, not user-set

ProductionBatch: PLANNED ──start──▶ IN_PROGRESS ──complete──▶ COMPLETED
                 PLANNED ──cancel──▶ CANCELLED

OutsourcingJob:  CREATED ──issue──▶ MATERIAL_ISSUED ──receive──▶ PARTIALLY_RECEIVED ──receive──▶ COMPLETED
                 CREATED ──cancel──▶ CANCELLED

Party:           ACTIVE ⇄ INACTIVE
Payment/Expense: RECORDED ──reverse──▶ REVERSED
WorkRecord:      UNPAID ─▶ PARTIALLY_PAID ─▶ PAID   — derived from allocations
Loan:            ACTIVE ─▶ CLOSED        Chit: ACTIVE ─▶ MATURED / CLOSED
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
| `MALFORMED_REQUEST` | 400 | Unparseable JSON / wrong types |
| `UNAUTHENTICATED` / `TOKEN_EXPIRED` / `INVALID_CREDENTIALS` | 401 | Auth problems |
| `ACCESS_DENIED` | 403 | Missing permission |
| `RESOURCE_NOT_FOUND` | 404 | Unknown id / business number |
| `DUPLICATE_BUSINESS_NUMBER` | 409 | Unique constraint on a business number |
| `DUPLICATE_REQUEST` | 409 | Idempotency key reused with a different payload |
| `CONCURRENT_MODIFICATION` | 409 | Optimistic lock (`@Version`) conflict |
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
| `INTERNAL_ERROR` | 500 | Unexpected. Log it with the correlation id and never leak the stack trace. |

**Convention:** 409 = conflicts with the *current state* (it could succeed later or with other data). 422 = the request is well-formed but semantically invalid regardless of state.

## 8. Open business questions (ask the owner before implementing the affected phase)

| # | Question | Blocks |
|---|---|---|
| Q1 | Is supplier payable based on **ordered** kg × rate, or **accepted** kg × rate? How do returns reduce it? | Phase 3 supplier payments |
| Q2 | When does a customer obligation start: at order **confirmation** or at **delivery**? | Phase 3 outstanding |
| Q3 | Supplier overdue penalty: per-day rate? fixed? per supplier? (must be configurable) | Phase 6 reminders |
| Q4 | Can a bank account go negative (overdraft)? | Phase 5 |
| Q5 | Rounding: are ₹ amounts rounded to whole rupees on bills, or kept to paise? | Phase 3 |
| Q6 | Chit bidding/payout calculation (`CHIT_BIDDING_CALCULATION` is still undefined) | Phase 5 chits |
| Q7 | Does direct raw-silk sale go through a customer order (recommended) or a separate sale? | Phase 3 |
| Q8 | Who else will log in at go-live (OWNER only per NFR 20.3, or STAFF too)? | Phase 1 seed data |

## Change Log
| Date | Change | Reason |
|---|---|---|
| 2026-09-29 | Initial consolidation from design docs Steps 4–8 | Start of implementation phase |
