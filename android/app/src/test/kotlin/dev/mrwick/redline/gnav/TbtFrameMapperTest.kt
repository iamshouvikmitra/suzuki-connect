package dev.mrwick.redline.gnav

import com.google.android.libraries.mapsplatform.turnbyturn.model.DrivingSide
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class TbtFrameMapperTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    // 2026-06-01 10:00:00 IST
    private val t0 = java.time.ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun snap(
        maneuver: Int? = Maneuver.TURN_RIGHT, state: Int = NavState.ENROUTE, toStep: Int? = 220,
        toDest: Int? = 12_340, secs: Int? = 25 * 60, exit: Int? = null,
    ) = TbtSnapshot(state, maneuver, exit, DrivingSide.LEFT, toStep, toDest, secs, false, "MG Road", t0)

    @Test fun `en route right turn produces translated cluster byte and formatted fields`() {
        val p = TbtFrameMapper.toParsedNavData(snap(), previousManeuverByte = 8, zone = zone)!!
        assertEquals(4, p.maneuverId)          // Mappls 3 -> cluster byte 4
        assertEquals("0220", p.distNext); assertEquals("M", p.distNextUnit)
        assertEquals("12.3", p.distTotal); assertEquals("K", p.distTotalUnit)
        assertEquals("1025AM", p.eta)
    }

    @Test fun `stopped yields null so the mux falls back to the idle clock`() {
        assertNull(TbtFrameMapper.toParsedNavData(snap(state = NavState.STOPPED), 8, zone))
    }

    @Test fun `rerouting keeps guidance on the cluster`() {
        assertEquals(4, TbtFrameMapper.toParsedNavData(snap(state = NavState.REROUTING), 8, zone)!!.maneuverId)
    }

    @Test fun `unknown maneuver and arrival keep the previous glyph`() {
        assertEquals(31, TbtFrameMapper.toParsedNavData(snap(maneuver = Maneuver.UNKNOWN), 31, zone)!!.maneuverId)
        assertEquals(31, TbtFrameMapper.toParsedNavData(snap(maneuver = Maneuver.DESTINATION), 31, zone)!!.maneuverId)
        assertEquals(31, TbtFrameMapper.toParsedNavData(snap(maneuver = null), 31, zone)!!.maneuverId)
    }

    @Test fun `distance formatting matches the OEM widths`() {
        assertEquals("0000" to "M", TbtFrameMapper.formatMeters(0))
        assertEquals("0085" to "M", TbtFrameMapper.formatMeters(85))
        assertEquals("0999" to "M", TbtFrameMapper.formatMeters(999))
        assertEquals("01.0" to "K", TbtFrameMapper.formatMeters(1000))
        assertEquals("01.2" to "K", TbtFrameMapper.formatMeters(1249))
        assertEquals("99.9" to "K", TbtFrameMapper.formatMeters(99_940))
        assertEquals("0100" to "K", TbtFrameMapper.formatMeters(100_000))
        assertEquals("9999" to "K", TbtFrameMapper.formatMeters(50_000_000))
    }

    @Test fun `eta wraps midnight and noon correctly`() {
        val midnight = java.time.ZonedDateTime.of(2026, 6, 1, 0, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("1205AM", TbtFrameMapper.formatEta(midnight, zone))
        val noon = java.time.ZonedDateTime.of(2026, 6, 1, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("1200PM", TbtFrameMapper.formatEta(noon, zone))
    }
}
