package dev.mrwick.redline.gnav

import com.google.android.libraries.mapsplatform.turnbyturn.model.DrivingSide
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import dev.mrwick.redline.nav.ManeuverMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleManeuverMapTest {

    @Test fun `basic turns map to the OEM left and right ids`() {
        assertEquals(0, GoogleManeuverMap.toMapplsId(Maneuver.TURN_LEFT))
        assertEquals(3, GoogleManeuverMap.toMapplsId(Maneuver.TURN_RIGHT))
        assertEquals(1, GoogleManeuverMap.toMapplsId(Maneuver.TURN_SLIGHT_LEFT))
        assertEquals(4, GoogleManeuverMap.toMapplsId(Maneuver.TURN_SLIGHT_RIGHT))
        assertEquals(2, GoogleManeuverMap.toMapplsId(Maneuver.TURN_SHARP_LEFT))
        assertEquals(5, GoogleManeuverMap.toMapplsId(Maneuver.TURN_SHARP_RIGHT))
        assertEquals(7, GoogleManeuverMap.toMapplsId(Maneuver.STRAIGHT))
        assertEquals(7, GoogleManeuverMap.toMapplsId(Maneuver.DEPART))
    }

    @Test fun `u-turns pick side by rotation`() {
        assertEquals(41, GoogleManeuverMap.toMapplsId(Maneuver.TURN_U_TURN_CLOCKWISE))
        assertEquals(6, GoogleManeuverMap.toMapplsId(Maneuver.TURN_U_TURN_COUNTERCLOCKWISE))
    }

    @Test fun `roundabouts use the per-exit ids when an exit count is known`() {
        assertEquals(65, GoogleManeuverMap.toMapplsId(Maneuver.ROUNDABOUT_CLOCKWISE, roundaboutExit = 1))
        assertEquals(67, GoogleManeuverMap.toMapplsId(Maneuver.ROUNDABOUT_LEFT_CLOCKWISE, roundaboutExit = 3))
        assertEquals(71, GoogleManeuverMap.toMapplsId(Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE, roundaboutExit = 7))
        assertEquals(72, GoogleManeuverMap.toMapplsId(Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE, roundaboutExit = 8))
        assertEquals(72, GoogleManeuverMap.toMapplsId(Maneuver.ROUNDABOUT_EXIT_CLOCKWISE, roundaboutExit = null))
    }

    @Test fun `direction-less off-ramp follows the driving side`() {
        assertEquals(73, GoogleManeuverMap.toMapplsId(Maneuver.OFF_RAMP_UNSPECIFIED, drivingSide = DrivingSide.LEFT))
        assertEquals(75, GoogleManeuverMap.toMapplsId(Maneuver.OFF_RAMP_UNSPECIFIED, drivingSide = DrivingSide.RIGHT))
    }

    @Test fun `unknown yields null so the cluster keeps its glyph`() {
        assertNull(GoogleManeuverMap.toMapplsId(Maneuver.UNKNOWN))
    }

    @Test fun `every mapped id except arrival and ferry has a cluster byte translation`() {
        for (m in Maneuver.DEPART..Maneuver.NAME_CHANGE) {
            val id = GoogleManeuverMap.toMapplsId(m, roundaboutExit = 2, drivingSide = DrivingSide.LEFT) ?: continue
            if (id == 40 || id == 36) continue // arrival / ferry: OEM table has no glyph, cluster keeps the last one
            assertNotNull("maneuver $m -> mappls $id has no cluster byte", ManeuverMap.mapplsIdToClusterByte(id, null))
        }
    }
}
