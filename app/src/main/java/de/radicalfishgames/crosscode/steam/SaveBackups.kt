package de.radicalfishgames.crosscode.steam

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Local backups of the save file. Before a sync (or a restore) replaces cloudsave/cc.save, the file
// it replaces is copied to cloudsave/backups/<yyyyMMdd-HHmmss>/cc.save, so a wrong choice or a bad
// sync is one restore away. Files only, no Steam. (Ported from Alabaster Droid, same author.)
object SaveBackups {

    class Backup(val name: String, val file: File) {
        // "2026-10-12 19:18", or the folder's name when it is not one of ours.
        val label: String
            get() = runCatching { LABEL.format(LocalDateTime.parse(name.take(15), STAMP)) }.getOrDefault(name)
        val bytes: Long get() = file.length()
    }

    fun dir(saveFile: File): File = File(saveFile.parentFile, "backups")

    fun stamp(now: LocalDateTime = LocalDateTime.now()): String = STAMP.format(now)

    // Copies the save aside before it is replaced. Does nothing when there is no save, or it is empty.
    // `tag` says where the copy came from ("cloud" for a Steam Cloud save kept before an upload).
    fun backup(saveFile: File, stamp: String = stamp(), tag: String? = null): File? {
        if (!saveFile.isFile || saveFile.length() == 0L) return null
        return store(saveFile, saveFile.readBytes(), stamp, tag)
    }

    // Keeps `bytes` as a backup of its own (used for the Steam Cloud save when this device's wins).
    fun store(saveFile: File, bytes: ByteArray, stamp: String = stamp(), tag: String? = null): File? {
        if (bytes.isEmpty()) return null
        val root = dir(saveFile)
        var name = if (tag == null) stamp else "$stamp-$tag"
        var n = 1
        while (File(root, name).exists()) name = (if (tag == null) stamp else "$stamp-$tag") + "-" + (++n)
        val target = File(File(root, name), saveFile.name)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        prune(saveFile)
        return target
    }

    // The backups, newest first.
    fun list(saveFile: File): List<Backup> =
        dir(saveFile).listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.name }.mapNotNull { d ->
            File(d, saveFile.name).takeIf { it.isFile && it.length() > 0 }?.let { Backup(d.name, it) }
        }

    // Puts backup `name` back as the save. The save it replaces is backed up first, so a restore can
    // itself be undone. Returns false when there is no such backup.
    fun restore(saveFile: File, name: String, stamp: String = stamp()): Boolean {
        val chosen = list(saveFile).firstOrNull { it.name == name } ?: return false
        val bytes = chosen.file.readBytes()
        backup(saveFile, stamp)
        saveFile.parentFile?.mkdirs()
        val tmp = File(saveFile.parentFile, saveFile.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(saveFile)) { saveFile.writeBytes(bytes); tmp.delete() }
        return true
    }

    // Keeps the newest KEEP backups; their names sort by time.
    fun prune(saveFile: File) {
        dir(saveFile).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }?.drop(KEEP)
            ?.forEach { it.deleteRecursively() }
    }

    const val KEEP = 8
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    private val LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
}
