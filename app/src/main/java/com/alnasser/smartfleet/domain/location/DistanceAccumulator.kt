package com.alnasser.smartfleet.domain.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GpsSample(val id: String, val timeMillis: Long, val latitude: Double, val longitude: Double, val accuracyMeters: Double, val mock: Boolean = false)
data class DistancePolicy(val maxAccuracyMeters: Double = 50.0, val minimumMovementMeters: Double = 12.0, val maxSpeedMetersPerSecond: Double = 45.0, val maxGapMillis: Long = 120_000L) {
    init {
        require(maxAccuracyMeters.isFinite() && maxAccuracyMeters > 0)
        require(minimumMovementMeters.isFinite() && minimumMovementMeters > 0)
        require(maxSpeedMetersPerSecond.isFinite() && maxSpeedMetersPerSecond > 0 && maxGapMillis > 0)
    }
}
enum class SampleStatus { ANCHOR, MOVEMENT, STATIONARY, DUPLICATE, INVALID, INACCURATE, MOCK, OUT_OF_ORDER, IMPOSSIBLE_JUMP, GAP }
data class DistanceResult(val status: SampleStatus, val segmentMeters: Double, val totalMeters: Double)

/** Mission-scoped pure calculation. Persist samples and deduplicate independently across restarts. */
class DistanceAccumulator(private val policy: DistancePolicy = DistancePolicy()) {
    private var anchor: GpsSample? = null
    private var lastObservedMillis: Long? = null
    private val seen = mutableSetOf<String>()
    var totalMeters: Double = 0.0; private set

    fun add(sample: GpsSample): DistanceResult {
        fun result(status: SampleStatus, segment: Double = 0.0) = DistanceResult(status, segment, totalMeters)
        if (sample.id.isBlank() || sample.id.length > 128 || sample.timeMillis < 0 || !sample.latitude.isFinite() ||
            !sample.longitude.isFinite() || sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0 ||
            !sample.accuracyMeters.isFinite() || sample.accuracyMeters <= 0) return result(SampleStatus.INVALID)
        if (!seen.add(sample.id)) return result(SampleStatus.DUPLICATE)
        if (sample.mock) return result(SampleStatus.MOCK)
        if (sample.accuracyMeters > policy.maxAccuracyMeters) return result(SampleStatus.INACCURATE)
        if (lastObservedMillis != null && sample.timeMillis <= lastObservedMillis!!) return result(SampleStatus.OUT_OF_ORDER)
        lastObservedMillis = sample.timeMillis
        val previous = anchor ?: run { anchor = sample; return result(SampleStatus.ANCHOR) }
        val elapsed = sample.timeMillis - previous.timeMillis
        if (elapsed > policy.maxGapMillis) { anchor = sample; return result(SampleStatus.GAP) }
        val distance = haversineMeters(previous.latitude, previous.longitude, sample.latitude, sample.longitude)
        val movementFloor = max(policy.minimumMovementMeters, (previous.accuracyMeters + sample.accuracyMeters) / 2.0)
        if (distance <= movementFloor) return result(SampleStatus.STATIONARY)
        if (distance / (elapsed / 1000.0) > policy.maxSpeedMetersPerSecond) return result(SampleStatus.IMPOSSIBLE_JUMP)
        anchor = sample
        totalMeters += distance
        return result(SampleStatus.MOVEMENT, distance)
    }
}

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = Math.PI / 180.0
    val dLat = (lat2 - lat1) * r
    val dLon = (lon2 - lon1) * r
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * 6_371_008.8 * asin(sqrt(min(1.0, max(0.0, a))))
}
