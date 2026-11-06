package com.alnasser.smartfleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelAmountTest {
    @Test fun parsesPersianArabicAndDecimalLitersExactly() {
        assertEquals(100000L, parseLitersToMl("۱۰۰"))
        assertEquals(1234L, parseLitersToMl("١٫٢٣٤"))
        assertEquals(1L, parseLitersToMl("0.001"))
        assertEquals(2147483647L, parseLitersToMl("2147483.647"))
    }
    @Test fun rejectsZeroMalformedOverprecisionAndOverflow() {
        listOf("0", "-1", "1.0000", "1e3", "1,000", "NaN", "2147483.648", "", ".5").forEach {
            assertNull("Expected invalid: $it", parseLitersToMl(it))
        }
    }
}
