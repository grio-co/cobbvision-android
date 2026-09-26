package com.cobbvision.companion.service

import com.cobbvision.companion.data.DriveSession
import com.cobbvision.companion.data.TrackPoint

/**
 * Pure (Android-free) recording logic shared by [LocationService] and the unit tests:
 * which fixes are kept, how they are appended to a [DriveSession], and how a session
 * is finalised.
 */
object TrackRecorder {

    /** Fixes with a horizontal accuracy worse than this (metres) are discarded. */
    const val MAX_ACCURACY_M = 30f

    /** A fix is kept when its accuracy is known (>= 0) and within [MAX_ACCURACY_M]. */
    fun isAcceptable(point: TrackPoint): Boolean = point.accuracyM in 0f..MAX_ACCURACY_M

    /**
     * Returns [session] with the acceptable [points] appended in order. The same
     * instance is returned when nothing was accepted, so observers are not notified.
     */
    fun append(session: DriveSession, points: List<TrackPoint>): DriveSession {
        val accepted = points.filter(::isAcceptable)
        if (accepted.isEmpty()) return session
        return session.copy(trackPoints = session.trackPoints + accepted)
    }

    /** Marks [session] as ended at [endedAtMs]. */
    fun finish(session: DriveSession, endedAtMs: Long = System.currentTimeMillis()): DriveSession =
        session.copy(endedAtMs = endedAtMs)
}
