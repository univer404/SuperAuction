package com.superauction.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceParserTest {
    @Test void parsesPlainAndSuffixPrices() {
        assertEquals(500.0, PriceParser.parse("500"));
        assertEquals(5_000.0, PriceParser.parse("5k"));
        assertEquals(2_500.0, PriceParser.parse("2.5k"));
        assertEquals(1_000_000.0, PriceParser.parse("1m"));
        assertEquals(2_500_000.0, PriceParser.parse("2.5m"));
    }

    @Test void rejectsInvalidPrices() {
        assertThrows(IllegalArgumentException.class, () -> PriceParser.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> PriceParser.parse("-5k"));
        assertThrows(IllegalArgumentException.class, () -> PriceParser.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> PriceParser.parse(" "));
    }
}