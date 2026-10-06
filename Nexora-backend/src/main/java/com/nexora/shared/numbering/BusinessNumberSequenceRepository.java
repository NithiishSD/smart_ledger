package com.nexora.shared.numbering;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

// Package-private interface: nothing outside this package can use the repository.
// Other modules must go through BusinessNumberGenerator (modules talk via services only).
// Spring Data creates the implementation at startup: we only declare the methods.
interface BusinessNumberSequenceRepository extends JpaRepository<BusinessNumberSequence, UUID> {

    // PESSIMISTIC_WRITE makes Hibernate append "FOR UPDATE": the row is locked until the
    // current transaction commits or rolls back. Other transactions that run the same
    // query WAIT here. This is what makes number generation safe under concurrency.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BusinessNumberSequence s where s.prefix = :prefix and s.seqYear = :seqYear")
    Optional<BusinessNumberSequence> findForUpdate(@Param("prefix") String prefix,
                                                   @Param("seqYear") int seqYear);

    // Creates the counter for a new (prefix, year) the first time it is needed.
    // "ON CONFLICT DO NOTHING": if another transaction created it first, do nothing
    // instead of failing with a unique-constraint error. Safe to call every time.
    // nativeQuery = true: plain SQL, because ON CONFLICT is PostgreSQL syntax, not JPQL.
    // @Modifying: tells Spring Data this statement changes data (INSERT/UPDATE/DELETE).
    @Modifying
    @Query(value = """
            insert into business_number_sequences (prefix, seq_year)
            values (:prefix, :seqYear)
            on conflict (prefix, seq_year) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(@Param("prefix") String prefix, @Param("seqYear") int seqYear);
}
