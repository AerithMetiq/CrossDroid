package de.radicalfishgames.crosscode.steam

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// The decisions of the save sync, kept apart from Steam and Android so they can be unit-tested.
//
// CrossCode has one save file (cc.save). The sync is three-way: besides this device's save and the
// Steam Cloud save it knows the BASE, the SHA-1 both had the last time they matched (lastSyncedSha).
// A side that still matches the base has not changed, so when only one side changed that side wins
// without asking, and when both did it is a conflict the player settles.
object SaveSync {

    enum class Action {
        NOTHING,     // no save anywhere: a fresh start
        SAME,        // both sides hold the same save
        DOWNLOAD,    // Steam Cloud's is newer, or this device has none
        UPLOAD,      // this device's is newer, or Steam Cloud has none (writes to Steam)
        CONFLICT     // both changed since they last matched, or they never matched
    }

    fun decide(localSha: String?, cloudSha: String?, baseSha: String?): Action = when {
        localSha == null && cloudSha == null -> Action.NOTHING
        localSha == null -> Action.DOWNLOAD
        cloudSha == null -> Action.UPLOAD
        localSha == cloudSha -> Action.SAME
        localSha == baseSha -> Action.DOWNLOAD
        cloudSha == baseSha -> Action.UPLOAD
        else -> Action.CONFLICT
    }

    data class Side(val sha: String?, val bytes: Long, val modifiedMs: Long)

    // The dry-run report: what PLAY would do with the save right now, and the facts behind it. It
    // holds sizes, times and checksum prefixes only, so it can be shared as it is.
    fun report(local: Side, cloud: Side, baseSha: String?, uploadsOn: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val what = when (decide(local.sha, cloud.sha, baseSha)) {
            Action.NOTHING -> "There is no save on this device or in Steam Cloud. PLAY would start fresh."
            Action.SAME -> "The save on this device matches Steam Cloud. PLAY would change nothing."
            Action.DOWNLOAD -> "PLAY would DOWNLOAD the Steam Cloud save. The save on this device is backed up first."
            Action.UPLOAD ->
                if (uploadsOn) "PLAY would UPLOAD this device's save to Steam Cloud."
                else "This device's save is newer, but it would NOT be uploaded: uploads are off."
            Action.CONFLICT -> "CONFLICT: both saves changed since they last matched. PLAY would ask which to keep."
        }
        return listOf(
            what,
            "",
            "This device:  ${side(local, zone)}",
            "Steam Cloud:  ${side(cloud, zone)}",
            "Last matched: ${baseSha?.take(8) ?: "never"}",
            "Uploads: ${if (uploadsOn) "on" else "off"}",
        ).joinToString("\n")
    }

    private fun side(s: Side, zone: ZoneId): String =
        if (s.sha == null) "no save"
        else "${String.format(Locale.US, "%.1f", s.bytes / 1024.0)} KiB, ${time(s.modifiedMs, zone)}, checksum ${s.sha.take(8)}"

    fun time(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (ms <= 0) "time unknown" else TIME.withZone(zone).format(Instant.ofEpochMilli(ms))

    // The launcher's standing line: when the save last matched Steam Cloud, and whether this
    // device's save has changed since.
    fun status(nowMs: Long, lastSyncMs: Long, localSha: String?, baseSha: String?): String {
        val synced = if (lastSyncMs <= 0) "Not synced with Steam Cloud yet" else "Synced with Steam Cloud ${ago(nowMs - lastSyncMs)}"
        return if (localSha != null && localSha != baseSha) "$synced · changed here since" else synced
    }

    private fun ago(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
            minutes < 48 * 60 -> (minutes / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    // Whether Steam's public build is another one than the installed one. Unknown on either side is
    // "no": an update is only offered when both are known and differ.
    fun updateAvailable(installedBuild: String?, steamBuild: String?): Boolean {
        val here = installedBuild?.toLongOrNull() ?: return false
        val there = steamBuild?.toLongOrNull() ?: return false
        return here != there
    }

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
}
