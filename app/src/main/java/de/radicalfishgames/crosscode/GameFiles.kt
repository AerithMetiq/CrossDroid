package de.radicalfishgames.crosscode

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import de.radicalfishgames.crosscode.steam.SaveSync
import java.io.File
import java.util.zip.ZipInputStream

// Where CrossCode and its save live on the device, and the small facts kept about them. Everything
// is under the app's own external files folder (Android/data/de.radicalfishgames.crosscode/files).
object GameFiles {

    fun gameDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "CrossCode")

    // The save Steam Cloud syncs: CloudSaveFeature seeds it into the game on launch and writes it back.
    fun saveFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), "cloudsave/cc.save")

    // "Installed" = game assets AND a mod-loader entry. A partial depot download has assets/ but no
    // ccloader (mods are extracted only after the full download), so this won't mistake it for ready.
    // An update in progress removes the marker's "complete" line too (see GameDownloadService).
    fun installed(ctx: Context): Boolean {
        val cc = gameDir(ctx)
        val hasLoader = File(cc, "ccloader/main.html").exists() ||
            File(cc, "ccloader/index.html").exists() ||
            File(cc, "ccloader3/main.html").exists()
        return File(cc, "assets").exists() && hasLoader && !File(cc, UPDATING).exists()
    }

    // Whether the mod loader is in place (also true mid-update, when installed() is not).
    fun installedOrUpdating(ctx: Context): Boolean {
        val cc = gameDir(ctx)
        return File(cc, "ccloader/main.html").exists() || File(cc, "ccloader/index.html").exists() ||
            File(cc, "ccloader3/main.html").exists()
    }

    // Written after a download that ran to the end: the Steam build it fetched.
    private const val MARKER = "installed.txt"
    // Present while an update or verification is rewriting files: the game is not playable until
    // the run finishes and removes it.
    private const val UPDATING = "updating.txt"

    fun installedBuild(ctx: Context): String? = runCatching {
        File(gameDir(ctx), MARKER).readLines().firstOrNull { it.startsWith("build=") }?.substringAfter('=')
    }.getOrNull()

    fun markInstalled(ctx: Context, build: String?, manifests: List<Pair<Int, Long>>) {
        File(gameDir(ctx), MARKER).writeText(
            "build=${build ?: "unknown"}\n" + manifests.joinToString("") { "depot ${it.first}=${it.second.toULong()}\n" }
        )
        File(gameDir(ctx), UPDATING).delete()
    }

    fun markUpdating(ctx: Context) {
        gameDir(ctx).mkdirs()
        File(gameDir(ctx), UPDATING).writeText("an update or verification was started and has not finished\n")
    }

    fun clearUpdating(ctx: Context) { File(gameDir(ctx), UPDATING).delete() }

    // How much storage the game takes, in bytes (walks the folder; call off the main thread).
    fun gameBytes(ctx: Context): Long = gameDir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // Deletes the downloaded game, the bundled mod loader and any mods installed into it. The save,
    // its backups and the settings are kept.
    fun deleteGame(ctx: Context) { gameDir(ctx).deleteRecursively() }

    // Extract the bundled mod loader + cc-font-fix (assets/mods.zip) into the game dir.
    fun provisionMods(ctx: Context) {
        val ccDir = gameDir(ctx)
        val root = ccDir.canonicalPath
        ctx.assets.open("mods.zip").use { ins ->
            ZipInputStream(ins).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(ccDir, entry.name)
                    if (out.canonicalPath.startsWith(root)) {          // zip-slip guard
                        if (entry.isDirectory) out.mkdirs()
                        else { out.parentFile?.mkdirs(); out.outputStream().use { zis.copyTo(it) } }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        Log.i("CrossCode", "[Depot] mod loader provisioned")
    }

    /* ---- settings and small facts ---- */

    // The login and sync bookkeeping (file "steam").
    fun steamPrefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("steam", Context.MODE_PRIVATE)

    // The Settings screen's values (the file PreferenceFragment writes).
    fun settings(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("de.radicalfishgames.crosscode_preferences", Context.MODE_PRIVATE)

    // Whether this device's save is uploaded to Steam Cloud. On by default, as CrossDroid always did;
    // off makes the sync download-only.
    fun cloudUploads(ctx: Context) = settings(ctx).getBoolean("steam_cloud_uploads", true)

    // Whether Steam shows the account as playing CrossCode while the game runs. Off by default: it
    // writes to Steam (friends see it, play time is counted).
    fun playingStatus(ctx: Context) = settings(ctx).getBoolean("steam_playing_status", false)

    // Whether there are touch controls at all. Off: never shown, whatever else is set.
    fun touchControls(ctx: Context) = settings(ctx).getBoolean("touch_controls", true)

    // The frame-rate limit, FrameRate.OFF for none. Stored as a string because ListPreference does.
    fun fpsLimit(ctx: Context): Int = settings(ctx).getString("fps_limit", "0")?.toIntOrNull() ?: 0
    fun setFpsLimit(ctx: Context, fps: Int) = settings(ctx).edit().putString("fps_limit", fps.toString()).apply()

    // The public build id Steam last reported, kept so an update is still offered when offline.
    fun steamBuild(ctx: Context): String? = steamPrefs(ctx).getString("steamBuild", null)
    fun setSteamBuild(ctx: Context, build: String) = steamPrefs(ctx).edit().putString("steamBuild", build).apply()

    fun updateAvailable(ctx: Context): Boolean =
        installed(ctx) && SaveSync.updateAvailable(installedBuild(ctx), steamBuild(ctx))

    // The SHA-1 the save had when this device and Steam Cloud last matched.
    fun lastSyncedSha(ctx: Context): String? = steamPrefs(ctx).getString("lastSyncedSha", null)

    // When they last matched, in epoch milliseconds; 0 for never.
    fun lastSyncAt(ctx: Context): Long = steamPrefs(ctx).getLong("lastSyncAt", 0)
}
