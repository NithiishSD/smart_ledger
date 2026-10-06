package com.nexora.shared.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.nexora.support.AbstractIntegrationTest;

class BusinessNumberGeneratorTest extends AbstractIntegrationTest {

    @Autowired
    BusinessNumberGenerator generator;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Clock clock;

    // The generator needs a transaction (propagation MANDATORY). In production the use case
    // provides it; in tests we open one with TransactionTemplate.
    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private int year() {
        return Year.now(clock).getValue();
    }

    // Each test starts with empty counters, so tests never depend on each other's data.
    @BeforeEach
    void cleanCounters() {
        jdbc.update("delete from business_number_sequences");
    }

    @Test
    void next_firstCall_returnsNumberOneWithPrefixAndYear() {
        String number = tx().execute(status -> generator.next("PUR"));

        assertThat(number).isEqualTo("PUR-" + year() + "-0001");
    }

    @Test
    void next_calledRepeatedly_incrementsByOne() {
        String first = tx().execute(status -> generator.next("PUR"));
        String second = tx().execute(status -> generator.next("PUR"));

        assertThat(first).endsWith("-0001");
        assertThat(second).endsWith("-0002");
    }

    @Test
    void next_differentPrefixes_haveIndependentCounters() {
        tx().execute(status -> generator.next("PUR"));
        String order = tx().execute(status -> generator.next("ORD"));

        assertThat(order).isEqualTo("ORD-" + year() + "-0001");
    }

    @Test
    void next_whenTransactionRollsBack_numberIsNotLost() {
        // Gap-free requirement (ADR-007): a rolled-back use case must not burn a number.
        tx().execute(status -> {
            generator.next("PUR");        // would be ...-0001
            status.setRollbackOnly();     // the use case fails, so everything is undone
            return null;
        });

        String number = tx().execute(status -> generator.next("PUR"));

        assertThat(number).endsWith("-0001");
    }

    @Test
    void next_outsideTransaction_isRejected() {
        // MANDATORY propagation: no surrounding transaction means no lock, so refuse.
        assertThatThrownBy(() -> generator.next("PUR"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void next_invalidPrefix_isRejected() {
        assertThatThrownBy(() -> tx().execute(status -> generator.next("pur-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // THE concurrency test. 20 threads ask for a number at the same moment.
    // With the row lock every thread gets a different number and none is skipped.
    // (Without the lock, two threads would read the same counter and both get PUR-...-0007.)
    @Test
    void next_withManyParallelRequests_neverReturnsDuplicates() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);   // counts threads that are set up
        CountDownLatch go = new CountDownLatch(1);            // the starting gun
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();   // every thread waits here until all are ready
                return tx().execute(status -> generator.next("PUR"));
            }));
        }

        ready.await();
        go.countDown();       // release all threads at the same time

        Set<String> numbers = new HashSet<>();
        for (Future<String> future : futures) {
            numbers.add(future.get());   // get() rethrows if a thread failed
        }
        pool.shutdown();

        // 20 requests -> 20 DIFFERENT numbers (a Set drops duplicates) ...
        assertThat(numbers).hasSize(threads);
        // ... and they are exactly 0001..0020 with no gap.
        Set<String> expected = new HashSet<>();
        for (int i = 1; i <= threads; i++) {
            expected.add("PUR-%d-%04d".formatted(year(), i));
        }
        assertThat(numbers).isEqualTo(expected);
    }
}
