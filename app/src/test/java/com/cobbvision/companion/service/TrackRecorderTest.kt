package com.cobbvision.companion.service

import com.cobbvision.companion.data.DriveSession
import com.cobbvision.companion.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording path: fixes are filtered on accuracy and accumulated into a DriveSession. */
class TrackRecorderTest {

    private val t0 = 1_700_000_000_000L

    private fun point(
        lat: Double = 45.0,
        lon: Double = -122.0,
        accuracy: Float = 5f,
        speed: Float = 10f,
        altitude: Double = 100.0,
        offsetMs: Long = 0,
    ) = TrackPoint(
        latitude    = lat,
        longitude   = lon,
        altitudeM   = altitude,
        speedMS     = speed,
        accuracyM   = accuracy,
        timestampMs = t0 + offsetMs,
    )

    private fun session() = DriveSession(id = "s1", startedAtMs = t0)

    // ── Accumulation ──────────────────────────────────────────────────────────

    @Test
    fun `appends accepted points in order`() {
        val result = TrackRecorder.append(session(), listOf(point(lat = 1.0), point(lat = 2.0), point(lat = 3.0)))

        assertEquals(listOf(1.0, 2.0, 3.0), result.trackPoints.map { it.latitude })
        assertEquals(3, result.pointCount)
        assertEquals("s1", result.id)
        assertEquals(t0, result.startedAtMs)
    }

    @Test
    fun `accumulates across successive callbacks`() {
        var s = session()
        s = TrackRecorder.append(s, listOf(point(lat = 1.0)))
        s = TrackRecorder.append(s, listOf(point(lat = 2.0), point(lat = 3.0)))
        s = TrackRecorder.append(s, emptyList())

        assertEquals(listOf(1.0, 2.0, 3.0), s.trackPoints.map { it.latitude })
    }

    @Test
    fun `does not mutate the input session`() {
        val original = session()
        TrackRecorder.append(original, listOf(point()))
        assertTrue(original.trackPoints.isEmpty())
    }

    @Test
    fun `returns the same instance when nothing was accepted`() {
        val original = session()
        assertSame(original, TrackRecorder.append(original, emptyList()))
        assertSame(original, TrackRecorder.append(original, listOf(point(accuracy = 50f))))
    }

    // ── Accuracy filter ───────────────────────────────────────────────────────

    @Test
    fun `rejects fixes with poor or unknown accuracy`() {
        val result = TrackRecorder.append(
            session(),
            listOf(
                point(lat = 1.0, accuracy = 30.01f),   // just over the limit
                point(lat = 2.0, accuracy = -1f),      // unknown accuracy
                point(lat = 3.0, accuracy = 30f),      // boundary: kept
                point(lat = 4.0, accuracy = 0f),       // boundary: kept
                point(lat = 5.0, accuracy = 250f),
            ),
        )
        assertEquals(listOf(3.0, 4.0), result.trackPoints.map { it.latitude })
    }

    @Test
    fun `isAcceptable matches the 30 m limit`() {
        assertTrue(TrackRecorder.isAcceptable(point(accuracy = 0f)))
        assertTrue(TrackRecorder.isAcceptable(point(accuracy = TrackRecorder.MAX_ACCURACY_M)))
        assertFalse(TrackRecorder.isAcceptable(point(accuracy = TrackRecorder.MAX_ACCURACY_M + 0.01f)))
        assertFalse(TrackRecorder.isAcceptable(point(accuracy = -0.01f)))
    }

    // ── Finish ────────────────────────────────────────────────────────────────

    @Test
    fun `finish sets endedAt and keeps the points`() {
        val recording = TrackRecorder.append(session(), listOf(point(offsetMs = 0), point(offsetMs = 1_000)))
        assertNull(recording.endedAtMs)

        val finished = TrackRecorder.finish(recording, endedAtMs = t0 + 90_000)

        assertEquals(t0 + 90_000, finished.endedAtMs)
        assertEquals(2, finished.pointCount)
        assertEquals(90_000L, finished.durationMs)
        assertEquals(90L, finished.durationSeconds)
    }

    @Test
    fun `finish defaults to now`() {
        val before = System.currentTimeMillis()
        val finished = TrackRecorder.finish(session())
        assertNotNull(finished.endedAtMs)
        assertTrue(finished.endedAtMs!! >= before)
    }

    @Test
    fun `an empty session can be finished`() {
        val finished = TrackRecorder.finish(session(), endedAtMs = t0 + 5)
        assertEquals(0, finished.pointCount)
        assertEquals(5L, finished.durationMs)
    }

    // ── TrackPoint derived values ─────────────────────────────────────────────

    @Test
    fun `derived speeds clamp negative to zero`() {
        val unavailable = point(speed = -1f)
        assertEquals(0f, unavailable.speedKPH, 0f)
        assertEquals(0f, unavailable.speedMPH, 0f)

        val moving = point(speed = 10f)
        assertEquals(36f, moving.speedKPH, 0.001f)
        assertEquals(22.3694f, moving.speedMPH, 0.001f)
    }
}
