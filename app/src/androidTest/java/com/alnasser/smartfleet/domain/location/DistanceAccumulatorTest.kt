package com.alnasser.smartfleet.domain.location

import org.junit.Assert.*
import org.junit.Test

class DistanceAccumulatorTest {
    private fun sample(id: String, t: Long, lat: Double, lon: Double = 0.0, acc: Double = 5.0, mock: Boolean = false) = GpsSample(id, t, lat, lon, acc, mock)
    @Test fun computesHaversineAndSuppressesRepeatedSamples() {
        val tracker = DistanceAccumulator()
        assertEquals(SampleStatus.ANCHOR, tracker.add(sample("a", 1000, 0.0)).status)
        val result = tracker.add(sample("b", 121000, 0.01))
        assertEquals(SampleStatus.MOVEMENT, result.status)
        assertTrue(result.totalMeters in 1110.0..1114.0)
        assertEquals(SampleStatus.DUPLICATE, tracker.add(sample("b", 122000, 0.01)).status)
        assertEquals(result.totalMeters, tracker.totalMeters, 0.0001)
    }
    @Test fun ignoresJitterAndImplausibleJumpsWithoutCountingThem() {
        val t = DistanceAccumulator()
        t.add(sample("a", 1000, 0.0))
        assertEquals(SampleStatus.STATIONARY, t.add(sample("b", 5000, 0.00001)).status)
        assertEquals(SampleStatus.IMPOSSIBLE_JUMP, t.add(sample("c", 6000, 10.0)).status)
        assertEquals(0.0, t.totalMeters, 0.0)
        assertEquals(SampleStatus.MOVEMENT, t.add(sample("d", 21000, 0.0003)).status)
    }
    @Test fun gapsReanchorWithoutInventingDistance() {
        val t = DistanceAccumulator()
        t.add(sample("a", 1000, 0.0))
        assertEquals(SampleStatus.GAP, t.add(sample("b", 122000, 1.0)).status)
        assertEquals(0.0, t.totalMeters, 0.0)
        assertEquals(SampleStatus.MOVEMENT, t.add(sample("c", 132000, 1.0002)).status)
    }
    @Test fun filtersInvalidInaccurateMockAndOutOfOrderSamples() {
        val t = DistanceAccumulator()
        assertEquals(SampleStatus.INVALID, t.add(sample("a", 0, Double.NaN)).status)
        assertEquals(SampleStatus.INACCURATE, t.add(sample("b", 1, 0.0, acc = 200.0)).status)
        assertEquals(SampleStatus.MOCK, t.add(sample("c", 2, 0.0, mock = true)).status)
        assertEquals(SampleStatus.ANCHOR, t.add(sample("d", 100, 0.0)).status)
        assertEquals(SampleStatus.OUT_OF_ORDER, t.add(sample("e", 99, 0.1)).status)
        assertEquals(0.0, t.totalMeters, 0.0)
    }
}
