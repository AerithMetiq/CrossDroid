package de.radicalfishgames.crosscode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.radicalfishgames.crosscode.steam.SteamDepotDownloader
import java.util.concurrent.CopyOnWriteArraySet

// What the game download is doing, for whoever is showing it. The download runs in
// GameDownloadService, which outlives the launcher's screen; the launcher reads this and listens.
// Listeners are called on the main thread.
object GameDownloadState {
    @Volatile var running = false
        private set
    // Null until file data is moving.
    @Volatile var percent: Int? = null
        private set
    @Volatile var status = ""
        private set
    // How the last run ended, until someone has shown it (takeEnd).
    @Volatile private var end: String? = null

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)
    fun takeEnd(): String? = end.also { end = null }

    internal fun begin(text: String) { running = true; percent = null; status = text; end = null; changed() }
    internal fun update(text: String, percent: Int? = this.percent) { status = text; this.percent = percent; changed() }
    internal fun finish(text: String) { running = false; percent = null; status = text; end = text; changed() }

    private fun changed() { main.post { listeners.forEach { it() } } }
}

// Downloads, updates or verifies CrossCode in the background: a foreground service with a progress
// notification, so the ~1.5 GB download carries on when the launcher is left or the screen turns off.
//
// One run does all three jobs. Files already on disk are kept; a verify run, and a run that finds a
// newer build on Steam than the installed one, also hash them against Steam's manifest and fetch the
// ones that differ.
class GameDownloadService : Service() {

    @Volatile private var downloader: SteamDepotDownloader? = null
    @Volatile private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            downloader?.cancel()
            if (!GameDownloadState.running) stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification("Connecting to Steam…", null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!GameDownloadState.running) run(verify = intent?.getBooleanExtra(EXTRA_VERIFY, false) == true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        downloader?.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun run(verify: Boolean) {
        val prefs = GameFiles.steamPrefs(this)
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        if (account == null || token == null) {
            GameDownloadState.finish("Sign in to download CrossCode.")
            stopSelf()
            return
        }
        cancelled = false
        GameDownloadState.begin("Connecting to Steam…")
        acquireLocks()
        Thread {
            var end = "CrossCode is downloaded and ready."
            var ok = false
            try {
                /* A busy Steam server ("try another") is met with a fresh connection. */
                var dl = SteamDepotDownloader(account, token).also { downloader = it }
                var connected = dl.connect()
                var attempt = 1
                while (!connected && dl.busy && attempt < 3 && !cancelled) {
                    dl.disconnect()
                    Thread.sleep(1000L * attempt++)
                    dl = SteamDepotDownloader(account, token).also { downloader = it }
                    connected = dl.connect()
                }
                try {
                    if (!connected) {
                        end = "Couldn't reach Steam to download CrossCode. Press DOWNLOAD to try again."
                    } else {
                        val installedBuild = GameFiles.installedBuild(this)
                        var shown = -1
                        ok = dl.download(
                            GameFiles.gameDir(this),
                            verify = verify,
                            onBuild = { steamBuild ->
                                /* A different build than the installed one: this run is an update.
                                 * Until it finishes the files are a mix, so the game is marked not
                                 * playable, and every file is hashed (a changed file can keep its size). */
                                val update = installedBuild != null && steamBuild != null && installedBuild != steamBuild
                                if (update) GameFiles.markUpdating(this)
                                update
                            },
                            onProgress = { p ->
                                if (p.percent != shown) {
                                    shown = p.percent
                                    val text = (if (verify) "Checking CrossCode… " else "Downloading CrossCode… ") +
                                        "${p.percent}%  (${p.completedFiles}/${p.totalFiles})"
                                    GameDownloadState.update(text, p.percent)
                                    notify(notification(if (verify) "Checking CrossCode" else "Downloading CrossCode", p.percent))
                                }
                            },
                            log = { Log.i(TAG, "[Depot] $it") }
                        )
                        if (ok) {
                            GameDownloadState.update("Installing mod loader…")
                            if (!GameFiles.installedOrUpdating(this)) GameFiles.provisionMods(this)
                            GameFiles.markInstalled(this, dl.buildId, dl.manifests)
                            dl.buildId?.let { GameFiles.setSteamBuild(this, it) }
                        } else {
                            end = if (cancelled) "Download stopped. Press DOWNLOAD to carry on."
                            else "Some files could not be downloaded. Press DOWNLOAD to try again."
                        }
                    }
                } finally {
                    dl.disconnect()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[Depot] download failed", t)
                end = if (cancelled) "Download stopped. Press DOWNLOAD to carry on."
                else "Download failed: ${t.message ?: t.javaClass.simpleName}. Press DOWNLOAD to try again."
            } finally {
                downloader = null
            }
            releaseLocks()
            GameDownloadState.finish(end)
            stopForegroundCompat()
            /* Left behind (unless the user cancelled), so a download that ended while the launcher
             * was not on screen says how it ended. */
            if (!cancelled) notify(notification(end, null, ongoing = false), DONE_ID)
            stopSelf()
        }.apply { name = "SteamDownload"; start() }
    }

    private fun notification(text: String, percent: Int?, ongoing: Boolean = true): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Game download", NotificationManager.IMPORTANCE_LOW))
        }
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        /* The same intent the app icon sends: it brings the app forward as it is, without putting a
         * second launcher on top of the one that is there. */
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, SteamLoginActivity::class.java)
        val open = PendingIntent.getActivity(this, 0, launch, immutable)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("CrossDroid")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            builder.setProgress(100, percent ?: 0, percent == null)
            val stop = PendingIntent.getService(
                this, 1, Intent(this, GameDownloadService::class.java).setAction(ACTION_CANCEL), immutable
            )
            builder.addAction(0, "Cancel", stop)
        }
        return builder.build()
    }

    // Without the notification permission (Android 13+) this is silently dropped; the download
    // itself does not depend on it.
    private fun notify(notification: Notification, id: Int = NOTIFICATION_ID) {
        runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification) }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    // Keeps the processor and Wi-Fi awake for the download when the screen is off.
    private fun acquireLocks() {
        runCatching {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrossDroid:download").apply { acquire(6 * 60 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CrossDroid:download").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "CrossCode"
        private const val CHANNEL = "download"
        private const val NOTIFICATION_ID = 1
        private const val DONE_ID = 2
        private const val ACTION_CANCEL = "de.radicalfishgames.crosscode.CANCEL_DOWNLOAD"
        private const val EXTRA_VERIFY = "verify"

        // Starts a download (or, with `verify`, a check of every file); does nothing when one is running.
        fun start(context: Context, verify: Boolean = false) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(DONE_ID)
            ContextCompat.startForegroundService(
                context, Intent(context, GameDownloadService::class.java).putExtra(EXTRA_VERIFY, verify)
            )
        }

        fun cancel(context: Context) {
            if (GameDownloadState.running) {
                context.startService(Intent(context, GameDownloadService::class.java).setAction(ACTION_CANCEL))
            }
        }
    }
}
