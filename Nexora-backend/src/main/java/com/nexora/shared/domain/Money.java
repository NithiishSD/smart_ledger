package com.nexora.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Money rules (Rule 6): BigDecimal only, never double. Scale 2 matches the database
// column NUMERIC(14,2). Rounding is HALF_UP (2.345 -> 2.35).
// Open owner question Q5 (whole rupees or paise on bills) is NOT decided here:
// this helper keeps paise (2 decimals).
//
// "final" = nobody can extend this class. A private constructor = nobody can create one.
// Together they make a "utility class": only static methods, no objects.
public final class Money {

    // Number of digits after the decimal point for money.
    public static final int SCALE = 2;

    private Money() {
    }

    // Parses text such as "1250.5" into a BigDecimal with exactly 2 decimals.
    // Always pass a String: new BigDecimal(0.1) (a double) carries the double's inexactness.
    public static BigDecimal of(String value) {
        return round(new BigDecimal(value));
    }

    // Forces scale 2 using HALF_UP rounding. BigDecimal is immutable, so this returns a NEW value.
    public static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    // 0.00 with the correct scale.
    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }

    // True when value > 0. compareTo returns -1, 0 or 1; it ignores scale (2.0 vs 2.00).
    // Never use equals() for numeric comparison: it also compares scale.
    public static boolean isPositive(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0;
    }
}
