package com.superauction.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Parses plain prices and k/m suffixes without binary floating-point rounding. */
public final class PriceParser {
    private PriceParser() {}

    public static double parse(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Price is required.");
        String value = input.trim().toLowerCase(Locale.ROOT);
        BigDecimal multiplier = BigDecimal.ONE;
        if (value.endsWith("k")) {
            multiplier = BigDecimal.valueOf(1_000);
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = BigDecimal.valueOf(1_000_000);
            value = value.substring(0, value.length() - 1);
        }
        try {
            BigDecimal price = new BigDecimal(value).multiply(multiplier).setScale(2, RoundingMode.UNNECESSARY);
            if (price.signum() <= 0 || price.scale() > 2) throw new IllegalArgumentException("Price must be positive with at most two decimal places.");
            double result = price.doubleValue();
            if (!Double.isFinite(result)) throw new IllegalArgumentException("Price is too large.");
            return result;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid price. Examples: 500, 5k, 2.5k, 1m.", exception);
        }
    }
}