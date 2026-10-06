package com.nexora.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Weight rules (Rule 6): BigDecimal in kilograms, scale 3 (42.500 kg), matching the
// database column NUMERIC(12,3). Same shape as Money, only the scale differs.
public final class Weight {

    // Number of digits after the decimal point for weight in kg.
    public static final int SCALE = 3;

    private Weight() {
    }

    // Parses text such as "42.5" into a BigDecimal with exactly 3 decimals.
    public static BigDecimal of(String value) {
        return round(new BigDecimal(value));
    }

    // Forces scale 3 using HALF_UP rounding and returns a new value.
    public static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // 0.000 with the correct scale.
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }

    // True when value > 0. Uses compareTo, never equals.
    public static boolean isPositive(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0;
    }
}
