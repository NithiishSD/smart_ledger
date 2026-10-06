package com.nexora.shared.numbering;

import com.nexora.shared.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// @Entity = this class is mapped to a database table; one object = one row.
// @Table names the table explicitly (Hibernate's default naming would not match ours).
// It extends AuditableEntity, so id, created_at, updated_at, created_by and version come for free.
@Entity
@Table(name = "business_number_sequences")
public class BusinessNumberSequence extends AuditableEntity {

    // updatable = false: the key of the counter never changes after insert.
    @Column(nullable = false, length = 10, updatable = false)
    private String prefix;

    // Named seq_year, not "year": avoids any clash with SQL/HQL keywords and functions.
    @Column(name = "seq_year", nullable = false, updatable = false)
    private int seqYear;

    // The NEXT number to hand out (starts at 1).
    @Column(name = "next_value", nullable = false)
    private long nextValue;

    // Required by JPA (it creates objects by reflection) but protected so that
    // application code cannot create half-initialised rows. The rows are created by the
    // "insert if absent" query in the repository, not by "new".
    protected BusinessNumberSequence() {
    }

    // The only way to change the counter: a named method, no setter.
    // Package-private on purpose: only BusinessNumberGenerator may call it.
    long takeNext() {
        long value = nextValue;   // the number we hand out now
        nextValue++;              // dirty checking writes the new value on commit
        return value;
    }
}
