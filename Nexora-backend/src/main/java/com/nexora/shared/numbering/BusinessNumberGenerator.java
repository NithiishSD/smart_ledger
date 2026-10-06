package com.nexora.shared.numbering;

import java.time.Clock;
import java.time.Year;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// @Service = a Spring bean for application logic. Spring creates it once and injects it
// wherever it is needed. Usage from a use case:  String number = generator.next("PUR");
@Service
public class BusinessNumberGenerator {

    // Only capital letters, 2 to 10 long. Matches the CHECK constraint in the migration.
    private static final Pattern PREFIX_PATTERN = Pattern.compile("^[A-Z]{2,10}$");

    private final BusinessNumberSequenceRepository repository;
    private final Clock clock;

    // Constructor injection: Spring passes in the repository and the Clock bean.
    public BusinessNumberGenerator(BusinessNumberSequenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // MANDATORY = this method must be called inside an EXISTING transaction, otherwise Spring
    // throws IllegalTransactionStateException. Why: the row lock lasts until the transaction ends.
    //  - If the caller's use case rolls back, the counter increment rolls back too,
    //    so numbers are gap-free (ADR-007).
    //  - Without a surrounding transaction the lock would be released immediately and the
    //    "use case = one transaction" rule (Rule 4) would be broken.
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String prefix) {
        if (prefix == null || !PREFIX_PATTERN.matcher(prefix).matches()) {
            // A wrong prefix is a programming error, not a business error: no ErrorCode needed.
            throw new IllegalArgumentException("Invalid business number prefix: " + prefix);
        }

        // "Today's year" in the business time zone (Asia/Kolkata) from the injected Clock.
        int year = Year.now(clock).getValue();

        // 1. Make sure the counter row exists (does nothing if it already does).
        repository.insertIfAbsent(prefix, year);

        // 2. Lock the row (SELECT ... FOR UPDATE). Concurrent callers queue up here.
        BusinessNumberSequence sequence = repository.findForUpdate(prefix, year)
                .orElseThrow(() -> new IllegalStateException(
                        "Sequence row missing for " + prefix + "/" + year));

        // 3. Take the current value and increment the counter. Saved when the transaction commits.
        long value = sequence.takeNext();

        // 4. Format: PUR-2026-0001. %04d pads with zeros to at least 4 digits.
        return "%s-%d-%04d".formatted(prefix, year, value);
    }
}
