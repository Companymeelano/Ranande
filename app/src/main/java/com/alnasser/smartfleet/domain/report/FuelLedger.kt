package com.alnasser.smartfleet.domain.report

import java.math.BigInteger

/** Integer-only validation of a server-snapshotted ledger row. */
data class FuelLedgerEntry(
    val requestId: String, val vehicleId: String, val driverUid: String, val approvedBy: String,
    val approvedAtMillis: Long, val month: String, val litersMl: Long, val governmentMl: Long, val freeMl: Long,
    val governmentRate: Long, val freeRate: Long, val governmentAmount: Long, val freeAmount: Long, val totalAmount: Long,
) {
    fun isConsistent(): Boolean {
        if (requestId.isBlank() || vehicleId.isBlank() || driverUid.isBlank() || approvedBy.isBlank() ||
            !month.matches(Regex("[0-9]{4}(0[1-9]|1[0-2])")) || approvedAtMillis <= 0 || litersMl <= 0 ||
            listOf(governmentMl, freeMl, governmentRate, freeRate, governmentAmount, freeAmount, totalAmount).any { it < 0 }) return false
        fun n(value: Long) = BigInteger.valueOf(value)
        fun amount(ml: Long, rate: Long) = n(ml).multiply(n(rate)).add(BigInteger.valueOf(500)).divide(BigInteger.valueOf(1000))
        return n(governmentMl).add(n(freeMl)) == n(litersMl) &&
            amount(governmentMl, governmentRate) == n(governmentAmount) && amount(freeMl, freeRate) == n(freeAmount) &&
            n(governmentAmount).add(n(freeAmount)) == n(totalAmount)
    }
}
data class FuelLedgerSummary(val volumeMl: BigInteger, val governmentMl: BigInteger, val freeMl: BigInteger, val totalToman: BigInteger)
fun summarizeFuelLedger(entries: List<FuelLedgerEntry>): FuelLedgerSummary {
    require(entries.all { it.isConsistent() })
    fun sum(value: (FuelLedgerEntry) -> Long) = entries.fold(BigInteger.ZERO) { total, entry -> total.add(BigInteger.valueOf(value(entry))) }
    return FuelLedgerSummary(sum { it.litersMl }, sum { it.governmentMl }, sum { it.freeMl }, sum { it.totalAmount })
}
