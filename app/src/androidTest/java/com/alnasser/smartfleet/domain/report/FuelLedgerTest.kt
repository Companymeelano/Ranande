package com.alnasser.smartfleet.domain.report

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class FuelLedgerTest {
    private val row = FuelLedgerEntry("id", "vehicle", "driver", "manager", 1742515200000L, "140401", 100000, 70000, 30000, 300, 80000, 21000, 2400000, 2421000)
    @Test fun validatesSnapshotAndSummarizesWithoutOverflow() {
        assertTrue(row.isConsistent())
        assertFalse(row.copy(totalAmount = 1).isConsistent())
        assertFalse(row.copy(governmentMl = 70001).isConsistent())
        assertFalse(row.copy(month = "140413").isConsistent())
        val max = Long.MAX_VALUE
        val large = row.copy(litersMl = 1000, governmentMl = 1000, freeMl = 0, governmentRate = max, freeRate = 0, governmentAmount = max, freeAmount = 0, totalAmount = max)
        assertTrue(large.isConsistent())
        assertEquals(BigInteger.valueOf(max).multiply(BigInteger.TWO), summarizeFuelLedger(listOf(large, large)).totalToman)
        assertEquals(BigInteger.valueOf(200000), summarizeFuelLedger(listOf(row, row)).volumeMl)
    }
}
