package de.radicalfishgames.crosscode.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SaveSyncTest {

    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    @Test
    fun theSideThatChangedSinceTheLastMatchWins() {
        assertEquals(SaveSync.Action.SAME, SaveSync.decide(a, a, null))
        assertEquals(SaveSync.Action.DOWNLOAD, SaveSync.decide(a, b, a))
        assertEquals(SaveSync.Action.UPLOAD, SaveSync.decide(b, a, a))
        assertEquals(SaveSync.Action.CONFLICT, SaveSync.decide(b, c, a))
        assertEquals("never matched, and they differ", SaveSync.Action.CONFLICT, SaveSync.decide(a, b, null))
    }

    @Test
    fun aSaveOnOneSideOnly() {
        assertEquals(SaveSync.Action.NOTHING, SaveSync.decide(null, null, null))
        assertEquals(SaveSync.Action.DOWNLOAD, SaveSync.decide(null, a, null))
        assertEquals(SaveSync.Action.UPLOAD, SaveSync.decide(a, null, null))
    }

    @Test
    fun theReportSaysWhatPlayWouldDo() {
        val local = SaveSync.Side(b, 132429, 1791671640000)
        val cloud = SaveSync.Side(a, 130000, 1791340031000)
        val off = SaveSync.report(local, cloud, a, uploadsOn = false, zone = ZoneOffset.UTC)
        assertTrue(off.startsWith("This device's save is newer, but it would NOT be uploaded"))
        assertTrue(off.contains("This device:  129.3 KiB, 2026-10-10 22:34, checksum bbbbbbbb"))
        assertTrue(off.contains("Last matched: aaaaaaaa"))
        assertTrue(SaveSync.report(local, cloud, a, uploadsOn = true).startsWith("PLAY would UPLOAD"))
        assertTrue(SaveSync.report(local, cloud, c, uploadsOn = true).startsWith("CONFLICT"))
        assertTrue(SaveSync.report(SaveSync.Side(null, 0, 0), cloud, null, true).contains("This device:  no save"))
    }

    @Test
    fun theStandingLine() {
        val now = 1_000_000_000_000L
        assertEquals("Not synced with Steam Cloud yet", SaveSync.status(now, 0, null, null))
        assertEquals("Synced with Steam Cloud just now", SaveSync.status(now, now - 5_000, a, a))
        assertEquals("Synced with Steam Cloud 5 minutes ago · changed here since", SaveSync.status(now, now - 5 * 60_000, b, a))
        assertEquals("Synced with Steam Cloud 3 hours ago", SaveSync.status(now, now - 3 * 3_600_000L, a, a))
    }

    @Test
    fun anUpdateIsOfferedOnlyWhenBothBuildsAreKnownAndDiffer() {
        assertEquals(true, SaveSync.updateAvailable("20004663", "20009999"))
        assertEquals(false, SaveSync.updateAvailable("20004663", "20004663"))
        assertEquals(false, SaveSync.updateAvailable(null, "20009999"))
        assertEquals(false, SaveSync.updateAvailable("20004663", null))
    }
}
