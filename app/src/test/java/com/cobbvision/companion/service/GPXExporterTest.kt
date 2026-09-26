package com.cobbvision.companion.service

import com.cobbvision.companion.data.DriveSession
import com.cobbvision.companion.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * GPXExporter: a fixed DriveSession must serialise to the documented GPX 1.1 layout
 * (Garmin TrackPointExtension v2 speed), including empty and single-point tracks.
 */
class GPXExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 2023-11-14T22:13:20.000Z
    private val start = 1_700_000_000_000L

    private fun point(lat: Double, lon: Double, alt: Double, speed: Float, offsetMs: Long) =
        TrackPoint(
            latitude    = lat,
            longitude   = lon,
            altitudeM   = alt,
            speedMS     = speed,
            accuracyM   = 5f,
            timestampMs = start + offsetMs,
        )

    private val fixedSession = DriveSession(
        id          = "fixed",
        startedAtMs = start,
        endedAtMs   = start + 120_000,
        trackPoints = listOf(
            point(45.5231, -122.6765, 15.2,   12.34f, 1_500),
            point(45.524,  -122.677,  16.256, 13f,    2_500),
        ),
    )

    private fun export(session: DriveSession): Pair<File, String> {
        val file = GPXExporter.export(session, tmp.root)
        return file to file.readText()
    }

    private fun count(needle: String, haystack: String) = haystack.split(needle).size - 1

    private fun assertWellFormed(xml: String) {
        // Namespace-aware parse throws on malformed XML or undeclared prefixes.
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("gpx", doc.documentElement.localName)
        assertEquals("http://www.topografix.com/GPX/1/1", doc.documentElement.namespaceURI)
    }

    // ── Document structure ────────────────────────────────────────────────────

    @Test
    fun `header metadata and namespaces`() {
        val (_, gpx) = export(fixedSession)

        assertTrue(gpx.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(gpx.contains("""<gpx version="1.1""""))
        assertTrue(gpx.contains("""creator="CobbVision Companion/1.0""""))
        assertTrue(gpx.contains("""xmlns="http://www.topografix.com/GPX/1/1""""))
        assertTrue(gpx.contains("""xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2""""))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20.000Z</time>"))
        assertEquals(2, count("<name>CobbVision ", gpx))   // metadata + trk
        assertEquals(1, count("<trk>", gpx))
        assertEquals(1, count("<trkseg>", gpx))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
        assertWellFormed(gpx)
    }

    // ── Track points ──────────────────────────────────────────────────────────

    @Test
    fun `track points use the documented formatting`() {
        val (_, gpx) = export(fixedSession)

        assertEquals(2, count("<trkpt ", gpx))

        assertTrue(gpx.contains("""<trkpt lat="45.5231" lon="-122.6765">"""))
        assertTrue(gpx.contains("<ele>15.20</ele>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:21.500Z</time>"))
        assertTrue(gpx.contains("<gpxtpx:speed>12.3400</gpxtpx:speed>"))

        assertTrue(gpx.contains("""<trkpt lat="45.524" lon="-122.677">"""))
        assertTrue(gpx.contains("<ele>16.26</ele>"))   // 2 decimals, rounded
        assertTrue(gpx.contains("<time>2023-11-14T22:13:22.500Z</time>"))
        assertTrue(gpx.contains("<gpxtpx:speed>13.0000</gpxtpx:speed>"))

        assertEquals(2, count("<gpxtpx:TrackPointExtension>", gpx))
    }

    @Test
    fun `track point order is preserved`() {
        val (_, gpx) = export(fixedSession)
        assertTrue(gpx.indexOf("""lat="45.5231"""") < gpx.indexOf("""lat="45.524""""))
    }

    @Test
    fun `negative speed is clamped to zero`() {
        val (_, gpx) = export(
            DriveSession(startedAtMs = start, trackPoints = listOf(point(1.0, 2.0, 3.0, -1f, 0)))
        )
        assertTrue(gpx.contains("<gpxtpx:speed>0.0000</gpxtpx:speed>"))
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `empty track is a valid document with no points`() {
        val (_, gpx) = export(DriveSession(startedAtMs = start))

        assertEquals(0, count("<trkpt", gpx))
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("</trkseg>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20.000Z</time>"))
        assertWellFormed(gpx)
    }

    @Test
    fun `single point track`() {
        val (_, gpx) = export(
            DriveSession(startedAtMs = start, trackPoints = listOf(point(51.5, -0.12, 35.0, 0f, 250)))
        )

        assertEquals(1, count("<trkpt ", gpx))
        assertTrue(gpx.contains("""<trkpt lat="51.5" lon="-0.12">"""))
        assertTrue(gpx.contains("<ele>35.00</ele>"))
        assertTrue(gpx.contains("<time>2023-11-14T22:13:20.250Z</time>"))
        assertTrue(gpx.contains("<gpxtpx:speed>0.0000</gpxtpx:speed>"))
        assertWellFormed(gpx)
    }

    @Test
    fun `output is deterministic for the same session`() {
        val (_, a) = export(fixedSession)
        val (_, b) = export(fixedSession)
        assertEquals(a, b)
    }

    // ── File export ───────────────────────────────────────────────────────────

    @Test
    fun `export writes a file named after the session start`() {
        val (file, gpx) = export(fixedSession)

        assertEquals("cobbvision_1700000000000.gpx", file.name)
        assertEquals(tmp.root, file.parentFile)
        assertTrue(file.exists())
        assertTrue(gpx.isNotEmpty())
    }
}
