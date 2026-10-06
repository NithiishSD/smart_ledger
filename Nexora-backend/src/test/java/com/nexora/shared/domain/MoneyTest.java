package com.nexora.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

// Plain unit test: no Spring, no database, runs in milliseconds.
// Naming convention: method_condition_expectedResult.
class MoneyTest {

    @Test
    void round_whenExactlyHalf_roundsUp() {
        // HALF_UP: a tie goes up. 2.345 -> 2.35
        assertThat(Money.round(new BigDecimal("2.345"))).isEqualByComparingTo("2.35");
    }

    @Test
    void round_whenBelowHalf_roundsDown() {
        assertThat(Money.round(new BigDecimal("2.344"))).isEqualByComparingTo("2.34");
    }

    @Test
    void of_whenWholeNumber_hasScaleTwo() {
        // "10" must become 10.00 so it matches NUMERIC(14,2)
        assertThat(Money.of("10").scale()).isEqualTo(2);
    }

    @Test
    void add_decimalValues_isExactUnlikeDouble() {
        // With double, 0.1 + 0.2 = 0.30000000000000004. BigDecimal is exact.
        BigDecimal sum = Money.of("0.1").add(Money.of("0.2"));
        assertThat(sum).isEqualByComparingTo("0.30");
    }

    @Test
    void equals_differentScale_isFalseButCompareToIsZero() {
        BigDecimal a = new BigDecimal("2.0");
        BigDecimal b = new BigDecimal("2.00");
        assertThat(a.equals(b)).isFalse();     // equals also compares scale
        assertThat(a.compareTo(b)).isZero();   // compareTo compares the numeric value
    }

    @Test
    void isPositive_zeroAndNegative_returnFalse() {
        assertThat(Money.isPositive(Money.zero())).isFalse();
        assertThat(Money.isPositive(Money.of("-1"))).isFalse();
        assertThat(Money.isPositive(Money.of("0.01"))).isTrue();
    }
}
