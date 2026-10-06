-- Counters for human-readable business numbers such as PUR-2026-0001 (ADR-007).
-- One row per (prefix, year). The application locks the row with SELECT ... FOR UPDATE,
-- so two requests can never receive the same number.
CREATE TABLE business_number_sequences (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prefix      VARCHAR(10) NOT NULL,             -- e.g. PUR, ORD, PAY
    seq_year    INTEGER     NOT NULL,             -- the counter resets every year
    next_value  BIGINT      NOT NULL DEFAULT 1,   -- the NEXT number to hand out
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,                             -- nobody "acts" here, so it stays NULL
    version     BIGINT      NOT NULL DEFAULT 0,

    -- last line of defence: one counter per prefix and year, even if the code has a bug
    CONSTRAINT uq_business_number_sequences_prefix_year UNIQUE (prefix, seq_year),
    CONSTRAINT ck_business_number_sequences_prefix CHECK (prefix ~ '^[A-Z]{2,10}$'),
    CONSTRAINT ck_business_number_sequences_next_value CHECK (next_value >= 1)
);
