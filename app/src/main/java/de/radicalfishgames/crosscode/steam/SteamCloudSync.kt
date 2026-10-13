package de.radicalfishgames.crosscode.steam

import android.util.Log
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EPersonaState
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStatesCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

// Logs in with a stored refresh token and syncs Steam Cloud files for CrossCode (app 368340).
// PULL = download a cloud file to disk (the file CloudSaveFeature injects into localStorage before boot).
// Steam serves cloud files via a one-shot HTTPS URL; large files come back zipped.
//
// `purpose` picks the logon's loginID. Steam lets one account be logged on several times as long as
// the ids differ, and replaces a session whose id is used again; so jobs that can overlap (the
// launcher's sync, the game download, the "playing" status) each get their own.
class SteamCloudSync(
    private val accountName: String,
    private val refreshToken: String,
    private val purpose: Purpose = Purpose.SAVES
) {

    enum class Purpose { SAVES, DOWNLOAD, PLAYING }

    private val client = SteamClient()
    private val manager = CallbackManager(client)
    private val steamUser: SteamUser = client.getHandler(SteamUser::class.java)!!
    private val cloud: SteamCloud = client.getHandler(SteamCloud::class.java)!!

    // Achievement unlocks ride the same logged-on session (see AchievementSync.flush).
    val achievements: SteamAchievements = SteamAchievements().also { client.addHandler(it) }

    @Volatile private var pumping = false
    private var pumpThread: Thread? = null

    // OK = logged on. AUTH_FAILED = Steam answered but rejected the token (re-login needed).
    // NETWORK_FAILED = couldn't reach Steam at all (offline). Callers MUST treat NETWORK_FAILED as
    // "play the local save, keep the saved login" — NOT as an expired login (that would wipe the
    // token and refuse to launch when you're simply offline).
    enum class ConnectResult { OK, AUTH_FAILED, NETWORK_FAILED }

    // True when the last connect() failed only because the Steam server that answered was busy
    // ("try another server"); a fresh connection usually lands on one that is not (see open()).
    @Volatile var busy = false
        private set

    fun connect(timeoutSec: Long = 20): ConnectResult {
        val loggedOn = CountDownLatch(1)
        var result: EResult? = null

        manager.subscribe(ConnectedCallback::class.java) {
            steamUser.logOn(LogOnDetails().apply {
                username = accountName
                accessToken = refreshToken
                shouldRememberPassword = true
                loginID = LOGIN_ID + purpose.ordinal
            })
        }
        manager.subscribe(LoggedOnCallback::class.java) { cb ->
            result = cb.result
            loggedOn.countDown()
        }

        startPump()
        try {
            client.connect()
        } catch (t: Throwable) {
            Log.e("CrossCode", "[Steam] connect() threw (offline?)", t)
            return ConnectResult.NETWORK_FAILED
        }
        if (!loggedOn.await(timeoutSec, TimeUnit.SECONDS)) {
            Log.e("CrossCode", "[Steam] couldn't reach Steam (offline?)")
            return ConnectResult.NETWORK_FAILED
        }
        Log.i("CrossCode", "[Steam] logon result: $result")
        return when (result) {
            EResult.OK -> ConnectResult.OK
            // The server is busy, not the login wrong: must not be read as an expired login.
            EResult.TryAnotherCM, EResult.ServiceUnavailable, EResult.Timeout, EResult.Busy -> {
                busy = true
                ConnectResult.NETWORK_FAILED
            }
            else -> ConnectResult.AUTH_FAILED
        }
    }

    data class Profile(val personaName: String?, val avatarHashHex: String?)

    // After logon, fetch the local user's display name + avatar hash (the hash builds the
    // avatars.steamstatic.com URL). Asks for the account's own name and presence only and waits for
    // an answer that carries an avatar; it does not set the account "online" to friends.
    //
    // This used to go Online and take the first persona callback, which often arrives before the
    // avatar is known: the hash came back empty, Steam's default "?" picture was saved, and because
    // a picture then existed it was never fetched again.
    fun fetchProfile(timeoutSec: Long = 8): Profile? {
        val friends = client.getHandler(SteamFriends::class.java) ?: return null
        val ready = CountDownLatch(1)
        var name: String? = null
        var hashHex: String? = null
        val sub = manager.subscribe(PersonaStatesCallback::class.java) { cb ->
            if (friends.isLocalUser(cb.friendID)) {
                cb.name?.takeIf { it.isNotEmpty() }?.let { name = it }
                cb.avatarHash?.takeIf { h -> h.any { it.toInt() != 0 } }?.let {
                    hashHex = it.toHex()
                    ready.countDown()
                }
            }
        }
        try {
            client.steamID?.let { friends.requestFriendInfo(it, PERSONA_NAME or PERSONA_PRESENCE) }
        } catch (_: Throwable) {}
        ready.await(timeoutSec, TimeUnit.SECONDS)
        runCatching { sub?.close() }
        val finalName = name ?: runCatching { friends.getPersonaName() }.getOrNull()
        val finalHash = hashHex ?: runCatching {
            friends.getPersonaAvatar()?.takeIf { h -> h.any { it.toInt() != 0 } }?.toHex()
        }.getOrNull()
        Log.i("CrossCode", "[Steam] profile: name=$finalName avatar=$finalHash")
        return Profile(finalName, finalHash)
    }

    // Read-only: the build id of CrossCode's public branch on Steam, or null when Steam does not
    // say. The launcher compares it with the installed build to offer an update.
    fun publicBuildId(): String? = runCatching {
        val apps = client.getHandler(SteamApps::class.java)!!
        val token = apps.picsGetAccessTokens(APP_ID).toFuture().get(20, TimeUnit.SECONDS).appTokens[APP_ID] ?: 0L
        val info = apps.picsGetProductInfo(PICSRequest(APP_ID, token)).toFuture().get(20, TimeUnit.SECONDS)
        info.results.filterIsInstance<PICSProductInfoCallback>().firstNotNullOfOrNull { it.apps[APP_ID] }
            ?.keyValues?.get("depots")?.get("branches")?.get("public")?.get("buildid")?.value
    }.onFailure { Log.w("CrossCode", "[Steam] build check failed: ${it.message}") }.getOrNull()

    // WRITES TO STEAM: shows the account as online and playing CrossCode for as long as this
    // session stays logged on. Friends see it and Steam counts the play time. stopPlaying(), or
    // disconnecting, ends it. `onState` gets Steam's own word on it ("Online, game 368340") each
    // time Steam pushes this account's status.
    fun showPlaying(buildId: Int, processId: Int, onState: (String) -> Unit) {
        val friends = client.getHandler(SteamFriends::class.java) ?: return
        val apps = client.getHandler(SteamApps::class.java) ?: return
        manager.subscribe(PersonaStatesCallback::class.java) { cb ->
            if (friends.isLocalUser(cb.friendID)) onState("${cb.state}, game ${cb.gameAppID}")
        }
        friends.setPersonaState(EPersonaState.Online)
        Thread.sleep(1000)
        apps.notifyGamesPlayed(
            listOf(GamePlayedInfo(gameId = APP_ID.toLong(), processId = processId,
                ownerId = client.steamID?.accountID?.toInt() ?: 0, gameBuildId = buildId)),
            EOSType.AndroidUnknown
        )
    }

    fun stopPlaying() {
        runCatching { client.getHandler(SteamApps::class.java)?.notifyGamesPlayed(emptyList(), EOSType.AndroidUnknown) }
        runCatching { client.getHandler(SteamFriends::class.java)?.setPersonaState(EPersonaState.Offline) }
    }

    val isConnected: Boolean get() = client.isConnected

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // All Steam Cloud filenames for this app (to discover the exact save path).
    fun listFiles(): List<String> =
        cloud.getAppFileListChange(APP_ID).get().files.map { it.filename }

    // Cloud modification time of a file (epoch millis), or null if it isn't in the cloud.
    fun cloudTimestampMillis(filename: String): Long? {
        val list = cloud.getAppFileListChange(APP_ID).get()
        val file = list.files.firstOrNull { it.filename == filename } ?: return null
        return file.timestamp.time
    }

    data class CloudFile(val name: String, val shaHex: String, val size: Int, val timestampMillis: Long)

    // Locate the cloud save (canonical cc.save; skip Steam's escaped %...PERCENT...% variants) and
    // return its identity (SHA-1 + size + mtime), or null if there's no cloud save yet.
    fun findSave(): CloudFile? {
        val files = cloud.getAppFileListChange(APP_ID).get().files
        val f = files.firstOrNull { it.filename == "cc.save" }
            ?: files.firstOrNull { it.filename.endsWith("cc.save") && !it.filename.contains("PERCENT") }
            ?: return null
        val shaHex = f.shaFile?.joinToString("") { "%02x".format(it) } ?: ""
        return CloudFile(f.filename, shaHex, f.rawFileSize, f.timestamp.time)
    }

    // Download a cloud file to dest. Returns true on success.
    fun pull(filename: String, dest: File): Boolean {
        val bytes = fetch(filename) ?: return false
        dest.parentFile?.mkdirs()
        SaveBackups.backup(dest)       // keep the prior local save in cloudsave/backups before overwriting
        writeAtomically(dest, bytes)
        Log.i("CrossCode", "[Steam] pulled $filename (${bytes.size} bytes) -> ${dest.absolutePath}")
        return true
    }

    // A cloud file's bytes, or null. Read-only: nothing on this device or on Steam changes.
    fun fetch(filename: String): ByteArray? {
        val info = cloud.clientFileDownload(APP_ID, filename).get(30, TimeUnit.SECONDS)
        if (info.urlHost.isNullOrEmpty()) {
            Log.e("CrossCode", "[Steam] no download URL for $filename")
            return null
        }

        val scheme = if (info.useHttps) "https" else "http"
        val conn = URL("$scheme://${info.urlHost}${info.urlPath}").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        info.requestHeaders.forEach { conn.setRequestProperty(it.name, it.value) }

        var bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()

        // Steam zips the payload when the raw (uncompressed) size differs from the served size.
        if (info.rawFileSize > 0 && info.rawFileSize != info.fileSize && isZip(bytes)) {
            bytes = unzipSingleEntry(bytes)
        }

        return bytes
    }

    // Upload a local file to Steam Cloud. Returns:
    //   UNCHANGED       - cloud already byte-identical (Steam rejects a no-op commit), so skip.
    //   REFUSED_SMALLER - the cloud holds a substantial save and this one is < half its size:
    //                     almost certainly a fresh/partial save written because the cloud slots
    //                     never loaded. We refuse rather than clobber a good save; caller surfaces it.
    //   UPLOADED        - uploaded and committed.
    //   FAILED          - empty source, or an upload/commit error.
    enum class PushResult { UPLOADED, UNCHANGED, REFUSED_SMALLER, FAILED }

    fun push(filename: String, src: java.io.File): PushResult {
        val bytes = src.readBytes()
        if (bytes.isEmpty()) {
            Log.w("CrossCode", "[Steam] refusing to push an empty $filename")
            return PushResult.FAILED
        }
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes)
        val size = bytes.size

        val cloudFile = runCatching {
            cloud.getAppFileListChange(APP_ID).get().files.firstOrNull { it.filename == filename }
        }.getOrNull()
        if (cloudFile != null) {
            val cloudSha = cloudFile.shaFile
            if (cloudSha != null && cloudSha.contentEquals(sha)) {
                Log.i("CrossCode", "[Steam] push $filename: already up to date ($size bytes, sha match)")
                return PushResult.UNCHANGED
            }
            val cloudSize = cloudFile.rawFileSize
            if (cloudSize >= 4096 && size < cloudSize / 2) {
                Log.w("CrossCode", "[Steam] refusing to overwrite $cloudSize-byte cloud save with $size-byte save")
                return PushResult.REFUSED_SMALLER
            }
        }

        val info = cloud.beginFileUpload(
            APP_ID, size, size, sha, Date(), filename,
            /* platformsToSync */ UInt.MAX_VALUE.toInt(),
            /* cellId */ 0,
            /* canEncrypt */ false,
            /* isSharedFile */ false,
            /* deprecatedRealm */ null,
            /* uploadBatchId */ 0L
        ).get()

        var uploadedOk = true
        try {
            for (block in info.blockRequests) {
                val scheme = if (block.useHttps) "https" else "http"
                val conn = URL("$scheme://${block.urlHost}${block.urlPath}").openConnection() as HttpURLConnection
                conn.requestMethod = if (block.httpMethod == 2) "POST" else "PUT"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000
                block.requestHeaders.forEach { conn.setRequestProperty(it.name, it.value) }
                conn.setRequestProperty("Content-Type", "application/octet-stream")

                val body = block.explicitBodyData?.takeIf { it.isNotEmpty() }
                    ?: bytes.copyOfRange(block.blockOffset.toInt(), (block.blockOffset + block.blockLength).toInt())
                conn.outputStream.use { it.write(body) }

                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) {
                    uploadedOk = false
                    Log.e("CrossCode", "[Steam] block upload HTTP $code for $filename")
                    break
                }
            }
        } catch (t: Throwable) {
            uploadedOk = false
            Log.e("CrossCode", "[Steam] block upload failed for $filename", t)
        }

        // Commit even on failure (transferSucceeded=false) so Steam releases the upload slot.
        val committed = cloud.commitFileUpload(uploadedOk, APP_ID, sha, filename).get()
        Log.i("CrossCode", "[Steam] push $filename: $size bytes, uploadedOk=$uploadedOk committed=$committed")
        return if (uploadedOk && committed) PushResult.UPLOADED else PushResult.FAILED
    }

    fun disconnect() {
        try { steamUser.logOff() } catch (_: Throwable) {}
        try { client.disconnect() } catch (_: Throwable) {}
        stopPump()
    }

    private fun isZip(b: ByteArray) =
        b.size >= 4 && b[0].toInt() == 0x50 && b[1].toInt() == 0x4B && b[2].toInt() == 0x03 && b[3].toInt() == 0x04

    private fun unzipSingleEntry(zip: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            zis.nextEntry ?: return zip
            return zis.readBytes()
        }
    }

    // Write via a temp file + rename so a crash mid-write can never leave a half-written save.
    private fun writeAtomically(dest: File, bytes: ByteArray) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun startPump() {
        pumping = true
        pumpThread = Thread {
            while (pumping) manager.runWaitCallbacks(1000)
        }.apply { isDaemon = true; name = "SteamCloudCallbacks"; start() }
    }

    private fun stopPump() {
        pumping = false
        pumpThread?.join(2000)
        pumpThread = null
    }

    companion object {
        const val APP_ID = 368340  // CrossCode

        // Any stable non-zero value; Steam only compares it between sessions of one account.
        private const val LOGIN_ID = 0x43434431
        // EClientPersonaStateFlag bits: the name, and presence (which carries the avatar hash).
        private const val PERSONA_NAME = 2
        private const val PERSONA_PRESENCE = 16

        // Connects and logs on. A server that answers "busy / try another server" is common and
        // brief, so that answer is met with a fresh connection, up to `attempts` times. `hold` gets
        // each session as it is made, so the caller can disconnect it to cancel. The returned
        // session must be disconnect()ed whatever the result.
        fun open(account: String, token: String, purpose: Purpose = Purpose.SAVES, attempts: Int = 3,
                 hold: (SteamCloudSync) -> Unit = {}): Pair<SteamCloudSync, ConnectResult> {
            var sync = SteamCloudSync(account, token, purpose)
            hold(sync)
            var result = sync.connect()
            var attempt = 1
            while (result == ConnectResult.NETWORK_FAILED && sync.busy && attempt < attempts) {
                sync.disconnect()
                Thread.sleep(1000L * attempt)
                attempt++
                Log.i("CrossCode", "[Steam] server busy; trying another (attempt $attempt)")
                sync = SteamCloudSync(account, token, purpose)
                hold(sync)
                result = sync.connect()
            }
            return sync to result
        }

        // SHA-1 (hex) of a file's bytes, or null if it's missing/empty. Lets the launcher tell
        // whether the local save matches the cloud and/or the last-synced baseline.
        fun sha1Hex(f: File): String? =
            if (f.exists() && f.length() > 0)
                MessageDigest.getInstance("SHA-1").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            else null
    }
}
