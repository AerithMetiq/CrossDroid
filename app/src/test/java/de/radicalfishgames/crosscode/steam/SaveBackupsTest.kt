package de.radicalfishgames.crosscode.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveBackupsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun save(text: String?): File {
        val f = File(tmp.root, "cloudsave/cc.save")
        f.parentFile!!.mkdirs()
        if (text != null) f.writeText(text)
        return f
    }

    @Test
    fun aBackupIsMadeOnlyOfARealSave() {
        assertEquals(null, SaveBackups.backup(save(null)))
        assertEquals(null, SaveBackups.backup(save("")))
        val made = SaveBackups.backup(save("progress"), stamp = "20261012-191849")!!
        assertEquals("progress", made.readText())
        assertEquals("backups/20261012-191849/cc.save", made.relativeTo(File(tmp.root, "cloudsave")).invariantSeparatorsPath)
    }

    @Test
    fun backupsAreListedNewestFirstAndNeverOverwriteEachOther() {
        val f = save("one")
        SaveBackups.backup(f, stamp = "20261012-191849")
        f.writeText("two")
        SaveBackups.backup(f, stamp = "20261012-191849")
        SaveBackups.store(f, "from steam".toByteArray(), stamp = "20261013-080000", tag = "cloud")
        val list = SaveBackups.list(f)
        assertEquals(listOf("20261013-080000-cloud", "20261012-191849-2", "20261012-191849"), list.map { it.name })
        assertEquals("2026-10-13 08:00", list[0].label)
        assertEquals("two", list[1].file.readText())
    }

    @Test
    fun restoringPutsTheBackupBackAndKeepsWhatItReplaced() {
        val f = save("old")
        SaveBackups.backup(f, stamp = "20261012-191849")
        f.writeText("current")
        assertTrue(SaveBackups.restore(f, "20261012-191849", stamp = "20261013-090000"))
        assertEquals("old", f.readText())
        assertEquals("current", File(tmp.root, "cloudsave/backups/20261013-090000/cc.save").readText())
        assertFalse(SaveBackups.restore(f, "20200101-000000"))
    }

    @Test
    fun onlyTheNewestBackupsAreKept() {
        val f = save("x")
        for (i in 10..(10 + SaveBackups.KEEP + 2)) SaveBackups.backup(f, stamp = "20261012-1918$i")
        assertEquals(SaveBackups.KEEP, SaveBackups.list(f).size)
        assertEquals("20261012-1918${10 + SaveBackups.KEEP + 2}", SaveBackups.list(f).first().name)
    }
}
