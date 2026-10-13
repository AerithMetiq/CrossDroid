package de.radicalfishgames.crosscode.steam

import android.content.Context
import android.os.Process
import android.util.Log
import de.radicalfishgames.crosscode.GameFiles

// While the game is on screen, keeps a Steam session that shows the account as playing CrossCode.
// WRITES TO STEAM, so it only runs when the player turned it on (Settings, off by default) and the
// game was started online. GameActivity starts it when it comes to the front and stops it when it
// leaves; a lost connection is retried while it runs.
//
// (Unlocking achievements also reports the game as running for a few seconds, on its own session:
// see SteamAchievements. The two do not depend on each other.)
object SteamPresence {

    private const val TAG = "CrossCode"
    private const val RETRY_MS = 30_000L

    @Volatile private var worker: Thread? = null
    @Volatile private var session: SteamCloudSync? = null
    @Volatile private var running = false

    @Synchronized
    fun start(ctx: Context) {
        if (worker != null) return
        val app = ctx.applicationContext
        val prefs = GameFiles.steamPrefs(app)
        if (!GameFiles.playingStatus(app) || prefs.getBoolean("sessionOffline", false)) return
        val account = prefs.getString("account", null) ?: return
        val token = prefs.getString("token", null) ?: return
        val build = GameFiles.installedBuild(app)?.toIntOrNull() ?: 0
        running = true
        worker = Thread {
            while (running) {
                try {
                    val (sync, result) = SteamCloudSync.open(account, token, SteamCloudSync.Purpose.PLAYING) { session = it }
                    if (result == SteamCloudSync.ConnectResult.AUTH_FAILED) break
                    if (result == SteamCloudSync.ConnectResult.OK && running) {
                        sync.showPlaying(build, Process.myPid()) { Log.i(TAG, "[Presence] Steam shows this account as $it") }
                        Log.i(TAG, "[Presence] playing status sent to Steam")
                        while (running && sync.isConnected) Thread.sleep(1000)
                    }
                } catch (_: InterruptedException) {
                    /* stopping */
                } catch (t: Throwable) {
                    Log.i(TAG, "[Presence] ${t.message}")
                } finally {
                    session?.let { s -> if (!running) s.stopPlaying(); s.disconnect() }
                    session = null
                }
                if (running) runCatching { Thread.sleep(RETRY_MS) }
            }
            Log.i(TAG, "[Presence] playing status ended")
        }.apply { isDaemon = true; name = "SteamPresence"; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
