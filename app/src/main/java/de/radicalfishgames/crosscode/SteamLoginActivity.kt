package de.radicalfishgames.crosscode

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.radicalfishgames.crosscode.steam.SteamAuth
import de.radicalfishgames.crosscode.preferences.PreferencesActivity
import de.radicalfishgames.crosscode.steam.AchievementSync
import de.radicalfishgames.crosscode.steam.SaveBackups
import de.radicalfishgames.crosscode.steam.SaveSync
import de.radicalfishgames.crosscode.steam.SteamCloudSync
import de.radicalfishgames.crosscode.steam.SteamPresence
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

// The launcher home screen. Two states in one screen:
//   • signed out  -> username + password + "Sign in with Steam" + "Sign in with QR code"
//   • signed in   -> Steam avatar + "Signed in as <you>" + a big PLAY button
// PLAY pulls the latest Steam Cloud save and then launches the game; the game pushes back to the
// cloud on quit (see GameActivity.quitWithCloudSync). The whole screen is controller-navigable
// (D-pad to move, A to activate). The password only ever goes to JavaSteam locally on-device.
// NOTE: the refresh token is stored in plain prefs for this build; encrypt via Keystore before release.
class SteamLoginActivity : AppCompatActivity() {

    private lateinit var signedOutGroup: View
    private lateinit var signedInGroup: View
    private lateinit var username: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var accountLine: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadBar: ProgressBar
    private lateinit var signInButton: MaterialButton
    private lateinit var qrButton: MaterialButton
    private lateinit var playButton: MaterialButton
    private lateinit var offlineToggle: CheckBox
    private lateinit var avatar: ShapeableImageView

    @Volatile private var profileRefreshing = false
    private var profileRefreshed = false
    private lateinit var syncStatus: TextView

    // The game download runs in GameDownloadService; the launcher mirrors its state.
    private val downloading get() = GameDownloadState.running
    private val onDownloadChanged: () -> Unit = { showDownload() }

    private val prefs get() = getSharedPreferences("steam", MODE_PRIVATE)
    private val avatarFile get() = File(getExternalFilesDir(null), "profile/avatar.png")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_steam_login)

        // Blend the system bars into the gradient so the whole screen reads as one dark surface.
        window.statusBarColor = ContextCompat.getColor(this, R.color.launcher_bg_top)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.launcher_bg_bottom)

        signedOutGroup = findViewById(R.id.signed_out_group)
        signedInGroup = findViewById(R.id.signed_in_group)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        accountLine = findViewById(R.id.account_line)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        downloadBar = findViewById(R.id.download_bar)
        signInButton = findViewById(R.id.sign_in_button)
        qrButton = findViewById(R.id.qr_button)
        playButton = findViewById(R.id.play_button)
        offlineToggle = findViewById(R.id.offline_toggle)
        avatar = findViewById(R.id.avatar)
        syncStatus = findViewById(R.id.sync_status)

        // Keep the pixel-art logo crisp instead of bilinear-smeared when scaled up.
        (findViewById<ImageView>(R.id.logo).drawable as? BitmapDrawable)?.isFilterBitmap = false

        signInButton.setOnClickListener { signIn() }
        qrButton.setOnClickListener { loginWithQr() }
        playButton.setOnClickListener { play() }
        offlineToggle.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("playOffline", checked).apply()
            updatePlayLabel()
        }
        findViewById<MaterialButton>(R.id.sign_out_button).setOnClickListener { signOut() }
        findViewById<MaterialButton>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, PreferencesActivity::class.java))
        }

        renderState()
        if (prefs.getString("token", null) == null) {
            setStatus("Sign in with your Steam account to play with your cloud save.")
        }
    }

    override fun onStart() {
        super.onStart()
        GameDownloadState.listen(onDownloadChanged)
    }

    override fun onStop() {
        GameDownloadState.unlisten(onDownloadChanged)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        /* Back on the launcher: the game is not on screen, so the playing status ends. Settings
         * may have changed what PLAY does. */
        SteamPresence.stop()
        if (prefs.getString("token", null) != null) {
            updatePlayLabel()
            showSyncStatus()
        }
        showDownload()
    }

    // Show the right half of the UI for the current login state, set initial controller focus.
    private fun renderState() {
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        if (account != null && token != null) {
            signedOutGroup.visibility = View.GONE
            signedInGroup.visibility = View.VISIBLE
            offlineToggle.isChecked = playOffline
            updatePlayLabel()
            refreshAccountLine()
            loadAvatar()
            refreshProfile()
            showSyncStatus()
            playButton.requestFocus()
        } else {
            signedInGroup.visibility = View.GONE
            signedOutGroup.visibility = View.VISIBLE
            avatar.visibility = View.GONE
            if (username.text.isNullOrEmpty()) username.setText(account ?: "")
            qrButton.requestFocus()
        }
    }

    private fun refreshAccountLine() {
        val name = prefs.getString("persona", null) ?: prefs.getString("account", null) ?: return
        accountLine.text = signedInLabel(name)
    }

    // "● Signed in as <name>" — green dot, name in bold white.
    private fun signedInLabel(name: String): CharSequence {
        val s = SpannableStringBuilder()
        val dotEnd = s.append("● ").length
        s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.online_green)),
            0, dotEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.append("Signed in as ")
        val nameStart = s.length
        s.append(name)
        s.setSpan(StyleSpan(Typeface.BOLD), nameStart, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_primary)),
            nameStart, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun loadAvatar() {
        val bmp = if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.absolutePath) else null
        if (bmp != null) { avatar.setImageBitmap(bmp); avatar.visibility = View.VISIBLE }
        else avatar.visibility = View.GONE
    }

    // Manual "Play Offline" toggle: when on, PLAY skips the Steam round-trip and launches the local
    // save instantly. Saves still reconcile via lastSyncedSha the next time you PLAY online.
    private val playOffline: Boolean get() = prefs.getBoolean("playOffline", false)

    private fun updatePlayLabel() {
        val caption = findViewById<TextView>(R.id.play_caption)
        when {
            downloading -> {
                playButton.text = "✕  CANCEL"
                caption.text = "Downloading CrossCode · it carries on if you leave this screen" +
                    if (notificationsOn()) "" else "\nNotifications are off for CrossDroid, so there is no progress notification"
            }
            !GameFiles.installed(this) -> {
                playButton.text = "↓  DOWNLOAD"
                caption.text = "Downloads your own copy of CrossCode from Steam (about 1.5 GB)"
            }
            playOffline -> {
                playButton.text = "▶  PLAY OFFLINE"
                caption.text = "Plays your local save · no cloud sync"
            }
            GameFiles.updateAvailable(this) -> {
                playButton.text = "↑  UPDATE"
                caption.text = "A new build of CrossCode is on Steam"
            }
            else -> {
                playButton.text = "▶  PLAY"
                caption.text = if (GameFiles.cloudUploads(this)) "Pulls your latest save · uploads to Steam Cloud on quit"
                else "Pulls your latest save · uploads are off (Settings)"
            }
        }
    }

    // The standing line under PLAY: when the save last matched Steam Cloud, and whether it changed since.
    private fun showSyncStatus() {
        Thread {
            val text = SaveSync.status(
                System.currentTimeMillis(), GameFiles.lastSyncAt(this),
                SteamCloudSync.sha1Hex(GameFiles.saveFile(this)), lastSyncedSha
            )
            runOnUiThread { syncStatus.text = text }
        }.apply { isDaemon = true; name = "SyncStatus"; start() }
    }

    // Once per visit to the launcher, log on quietly to refresh the persona name and avatar, and to
    // ask Steam for CrossCode's current build (read-only) so an update can be offered.
    //
    // This used to run only while no avatar file existed. But a first fetch that came back without
    // an avatar saved Steam's default "?" picture, and from then on a picture existed, so the real
    // one was never fetched. Now the avatar is downloaded whenever its hash differs from the one on
    // disk, and the default picture is not saved at all.
    private fun refreshProfile() {
        if (profileRefreshed || profileRefreshing) return
        val account = prefs.getString("account", null) ?: return
        val token = prefs.getString("token", null) ?: return
        profileRefreshing = true
        Thread {
            try {
                val (sync, result) = SteamCloudSync.open(account, token)
                try {
                    if (result == SteamCloudSync.ConnectResult.OK) {
                        profileRefreshed = true
                        sync.fetchProfile()?.let { saveProfile(it) }
                        if (GameFiles.installed(this)) {
                            sync.publicBuildId()?.let { GameFiles.setSteamBuild(this, it) }
                            runOnUiThread { updatePlayLabel() }
                        }
                    }
                } finally { sync.disconnect() }
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] profile refresh failed", t)
            } finally { profileRefreshing = false }
        }.apply { isDaemon = true; name = "SteamProfile"; start() }
    }

    // Stores the persona name and, when its hash is not the one already on disk, the avatar.
    private fun saveProfile(p: SteamCloudSync.Profile) {
        p.personaName?.let { prefs.edit().putString("persona", it).apply() }
        val hash = p.avatarHashHex?.takeIf { it.length == 40 && it.any { c -> c != '0' } }
        if (hash != null && (hash != prefs.getString("avatarHash", null) || !avatarFile.exists())) {
            if (downloadAvatar(hash)) prefs.edit().putString("avatarHash", hash).apply()
        }
        runOnUiThread { loadAvatar(); refreshAccountLine() }
    }

    private fun signIn() {
        val u = username.text.toString().trim()
        val p = password.text.toString()
        if (u.isEmpty() || p.isEmpty()) { setStatus("Enter your Steam username and password."); return }
        setBusy(true, "Connecting to Steam…")
        Thread {
            try {
                val auth = SteamAuth()
                val result = auth.login(u, p, prefs.getString("guard", null), status = { setStatus(it) }) { wrong ->
                    promptForGuardCode(wrong)
                }
                storeLogin(result.accountName, result.refreshToken, result.guardData)
                runOnUiThread { password.setText(""); renderState() }
                setStatus("Signed in as ${result.accountName}. Syncing your cloud save…")
                setStatus(pullCloudSave(result.accountName, result.refreshToken))
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] login/sync failed", t)
                setStatus("Sign-in failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }.apply { isDaemon = true; name = "SteamLogin"; start() }
    }

    // QR login: render Steam's challenge URL as a QR the user scans with the Steam Mobile app.
    private fun loginWithQr() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Size everything from the space this window actually has, so the QR is never clipped.
        // (It used to be a fixed ~470dp-wide layout: fine on 16:9, but on a 4:3 handheld the
        // dialog is narrower than that and the QR on the right got cut off.) screenWidthDp/
        // screenHeightDp are the usable window size, so this also holds in split-screen.
        val screenW = resources.configuration.screenWidthDp
        val screenH = resources.configuration.screenHeightDp
        val pad = if (screenW < 600) 16 else 24
        val dialogW = minOf(screenW - 32, 600)                       // 16dp clear of each screen edge
        val contentW = dialogW - 2 * 24 - 2 * pad                     // minus dialog background insets + our padding
        val gap = 16
        val heightBudget = screenH - (56 + 2 * 24 + 2 * pad + 16)     // minus button bar, insets, padding, slack
        // The QR takes at most half the row (the text column flexes into the rest), never more than
        // fits vertically, capped at 240dp, and never below 96dp (still scannable at arm's length).
        val qrDp = minOf((contentW - gap) / 2, heightBudget, 240).coerceAtLeast(96)
        val sizePx = dp(qrDp)
        val qrPad = dp(6)
        val qrBitmapPx = sizePx - 2 * qrPad                           // drawn 1:1 inside the white border: no blur

        // Landscape-friendly: instructions on the left, QR on the right.
        val heading = TextView(this).apply {
            text = "Sign in with QR"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val instructions = TextView(this).apply {
            text = "In the Steam Mobile app, tap the QR-scan icon, scan this code, then tap Approve."
            setPadding(0, dp(10), 0, 0)
        }
        val qrStatus = TextView(this).apply {
            text = "Connecting to Steam…"
            setPadding(0, dp(14), 0, 0)
        }
        // Text column flexes (width 0 + weight 1) instead of a fixed 232dp, so it wraps to whatever
        // room the QR leaves.
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heading); addView(instructions); addView(qrStatus)
        }
        val qrView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(qrPad, qrPad, qrPad, qrPad)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(pad), dp(18), dp(pad), dp(18))
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qrView, LinearLayout.LayoutParams(sizePx, sizePx).apply { leftMargin = dp(gap) })
        }
        // Last-resort safety on very short windows: scroll rather than clip.
        val container = ScrollView(this).apply { addView(row) }

        val auth = SteamAuth()
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton("Cancel") { _, _ -> aborted.set(true); auth.abort() }
            .setOnCancelListener { aborted.set(true); auth.abort() }
            .show()
        // Give the dialog the width we planned for, instead of the theme's default.
        dialog.window?.setLayout(dp(dialogW), WindowManager.LayoutParams.WRAP_CONTENT)

        Thread {
            try {
                val result = auth.loginViaQr(
                    onChallengeUrl = { url ->
                        val bmp = runCatching { qrBitmap(url, qrBitmapPx) }.getOrNull()
                        runOnUiThread {
                            bmp?.let { qrView.setImageBitmap(it) }
                            qrStatus.text = "Scan with your Steam Mobile app, then tap Approve."
                        }
                    },
                    status = { s -> runOnUiThread { qrStatus.text = s } }
                )
                storeLogin(result.accountName, result.refreshToken, result.guardData)
                runOnUiThread {
                    runCatching { dialog.dismiss() }
                    renderState()
                    setStatus("Signed in as ${result.accountName}. Syncing your cloud save…")
                }
                setStatus(pullCloudSave(result.accountName, result.refreshToken))
            } catch (t: Throwable) {
                if (!aborted.get()) {
                    Log.e("CrossCode", "[Steam] QR login failed", t)
                    runOnUiThread {
                        runCatching { dialog.dismiss() }
                        setStatus("QR sign-in failed: ${t.message ?: t.javaClass.simpleName}")
                    }
                }
            }
        }.apply { isDaemon = true; name = "SteamQrLogin"; start() }
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = hashMapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }

    // Sync the save, then launch. Version-aware and non-destructive:
    //   • offline (can't reach Steam) -> play the LOCAL save, keep the saved login
    //   • only the cloud changed       -> pull it (prior local kept as .bak)
    //   • only this device changed      -> push it up, keep playing local
    //   • both changed since last sync  -> ASK which to use (the other is backed up)
    // Only an actual auth rejection clears the token.
    private fun play() {
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        if (account == null || token == null) { renderState(); return }
        if (downloading) {
            setStatus("Stopping the download…")
            GameDownloadService.cancel(this)
            return
        }
        if (!GameFiles.installed(this)) {
            startDownload()
            return
        }
        if (!playOffline && GameFiles.updateAvailable(this) && !updateDeclined) {
            askUpdate()
            return
        }
        updateDeclined = false
        if (playOffline) {
            // Deliberate offline: skip the cloud round-trip and launch the local save now.
            // lastSyncedSha reconciles everything the next time you PLAY with the toggle off.
            prefs.edit().putBoolean("sessionOffline", true).apply()   // exit won't try the cloud either
            setStatus("Playing offline — your progress syncs next time you play online.")
            launchGame()
            return
        }

        setBusy(true, "Syncing your save…")
        Thread {
            val dest = File(getExternalFilesDir(null), "cloudsave/cc.save")
            val localSha = SteamCloudSync.sha1Hex(dest)
            val uploads = GameFiles.cloudUploads(this)
            var launch = false
            var expired = false
            var conflict: ConflictInfo? = null
            var msg = ""
            val (sync, connected) = SteamCloudSync.open(account, token)
            try {
                when (connected) {
                    SteamCloudSync.ConnectResult.NETWORK_FAILED -> {
                        prefs.edit().putBoolean("sessionOffline", true).apply()   // exit skips the cloud attempt
                        if (localSha != null) { msg = "Offline — playing your local save."; launch = true }
                        else msg = "Can't reach Steam and there's no save on this device yet. Connect to the internet and try again."
                    }
                    SteamCloudSync.ConnectResult.AUTH_FAILED -> {
                        expired = true; msg = "Your Steam login expired. Please sign in again."
                    }
                    SteamCloudSync.ConnectResult.OK -> {
                        prefs.edit().putBoolean("sessionOffline", false).apply()  // online this session
                        // Achievements earned during an offline session (or a run that never reached
                        // the quit flow) are still queued — send them now. No-op when the queue is empty.
                        AchievementSync.flush(this, sync)
                        val cloud = sync.findSave()
                        when {
                            cloud == null -> {
                                msg = if (localSha != null) "No cloud save yet — playing your local save."
                                      else "No cloud save yet — starting fresh."
                                launch = true
                            }
                            localSha == cloud.shaHex -> {                       // already identical
                                lastSyncedSha = localSha; msg = "Save is up to date — launching…"; launch = true
                            }
                            localSha == null -> {                               // nothing local -> take cloud
                                if (sync.pull(cloud.name, dest)) {
                                    lastSyncedSha = SteamCloudSync.sha1Hex(dest); msg = "Cloud save downloaded — launching…"; launch = true
                                } else msg = "Couldn't download your cloud save. Try again."
                            }
                            else -> {
                                val base = lastSyncedSha
                                val localChanged = localSha != base
                                val cloudChanged = cloud.shaHex != base
                                when {
                                    !localChanged && cloudChanged -> {          // cloud moved on -> pull
                                        if (sync.pull(cloud.name, dest)) {
                                            lastSyncedSha = SteamCloudSync.sha1Hex(dest); msg = "Updated from Steam Cloud — launching…"; launch = true
                                        } else msg = "Couldn't download your cloud save. Try again."
                                    }
                                    localChanged && !cloudChanged && !uploads -> {   // local moved on, uploads off -> keep it here
                                        msg = "Your save here is newer than Steam Cloud's — not uploaded (uploads are off in Settings)."
                                        launch = true
                                    }
                                    localChanged && !cloudChanged -> {          // local moved on (e.g. offline progress) -> push
                                        msg = when (sync.push(cloud.name, dest)) {
                                            SteamCloudSync.PushResult.UPLOADED, SteamCloudSync.PushResult.UNCHANGED -> {
                                                lastSyncedSha = localSha; "Synced your progress to Steam Cloud — launching…"
                                            }
                                            SteamCloudSync.PushResult.REFUSED_SMALLER -> "Kept your cloud save (it looks more complete) — playing your local save."
                                            SteamCloudSync.PushResult.FAILED -> "Couldn't upload right now — playing local, will retry on quit."
                                        }
                                        launch = true
                                    }
                                    else -> {                                   // both changed (or no baseline) -> ask
                                        conflict = ConflictInfo(account, token, cloud.name, cloud.timestampMillis, dest.lastModified(), cloud.shaHex)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] play-sync failed", t)
                if (localSha != null) { msg = "Couldn't sync — playing your local save."; launch = true }
                else msg = "Couldn't sync: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                sync.disconnect()
            }
            runOnUiThread {
                showSyncStatus()
                if (conflict != null) { setBusy(false); showConflictDialog(conflict!!); return@runOnUiThread }
                setBusy(false, msg)
                if (expired) { prefs.edit().remove("token").apply(); renderState() }
                if (launch) launchGame()
            }
        }.apply { isDaemon = true; name = "SteamPlay"; start() }
    }

    // SHA-1 of the save the cloud and this device last agreed on — lets us tell who changed since.
    private var lastSyncedSha: String?
        get() = prefs.getString("lastSyncedSha", null)
        set(v) { prefs.edit().putString("lastSyncedSha", v).putLong("lastSyncAt", System.currentTimeMillis()).apply() }

    private data class ConflictInfo(val account: String, val token: String, val cloudName: String,
                                    val cloudTs: Long, val localTs: Long, val cloudSha: String)

    // Both the cloud save and this device's save changed since they last matched. Never auto-pick —
    // ask, and keep a backup of whichever isn't chosen so nothing is lost.
    private fun showConflictDialog(c: ConflictInfo) {
        val newer = if (c.cloudTs >= c.localTs) "the cloud save looks newer" else "this device's save looks newer"
        AlertDialog.Builder(this)
            .setTitle("Which save do you want?")
            .setMessage("Your save here and your Steam Cloud save have both changed since they last matched " +
                "($newer).\n\nSteam Cloud: saved ${SaveSync.time(c.cloudTs)}\nThis device: saved ${SaveSync.time(c.localTs)}\n\n" +
                "Pick one to play — the other is backed up (Settings → Restore a save backup), not deleted." +
                if (GameFiles.cloudUploads(this)) "" else "\n\nUploads are off, so choosing this device's save leaves Steam Cloud as it is.")
            .setCancelable(false)
            .setPositiveButton("This device's save") { _, _ -> resolveConflict(c, useLocal = true) }
            .setNegativeButton("Cloud save") { _, _ -> resolveConflict(c, useLocal = false) }
            .show()
    }

    private fun resolveConflict(c: ConflictInfo, useLocal: Boolean) {
        setBusy(true, if (useLocal) "Uploading your save…" else "Downloading your cloud save…")
        Thread {
            val dest = File(getExternalFilesDir(null), "cloudsave/cc.save")
            var msg: String; var launch = false
            val (sync, connected) = SteamCloudSync.open(c.account, c.token)
            try {
                if (connected == SteamCloudSync.ConnectResult.OK) {
                    if (useLocal && !GameFiles.cloudUploads(this)) {
                        // Uploads are off: keep playing this device's save and leave Steam Cloud alone.
                        // The cloud save it was weighed against becomes the baseline, so the question is
                        // not asked again until Steam Cloud changes.
                        lastSyncedSha = c.cloudSha
                        msg = "Using this device's save. Steam Cloud was left as it is (uploads are off)."
                        launch = true
                    } else if (useLocal) {
                        // keep the cloud save as a backup, then push the local one up
                        runCatching { sync.fetch(c.cloudName)?.let { SaveBackups.store(dest, it, tag = "cloud") } }
                        msg = when (sync.push(c.cloudName, dest)) {
                            SteamCloudSync.PushResult.UPLOADED, SteamCloudSync.PushResult.UNCHANGED -> {
                                lastSyncedSha = SteamCloudSync.sha1Hex(dest); "Using this device's save (cloud backed up)."
                            }
                            SteamCloudSync.PushResult.REFUSED_SMALLER -> "This device's save is much smaller — kept the cloud save to be safe. Playing local."
                            SteamCloudSync.PushResult.FAILED -> "Upload failed — playing local, will retry on quit."
                        }
                        launch = true
                    } else {
                        if (sync.pull(c.cloudName, dest)) {     // pull() backs the local save up first
                            lastSyncedSha = SteamCloudSync.sha1Hex(dest); msg = "Using your cloud save (local backed up)."; launch = true
                        } else msg = "Couldn't download the cloud save. Try again."
                    }
                } else {
                    msg = "Couldn't reach Steam — playing your local save."
                    launch = dest.exists() && dest.length() > 0
                }
            } catch (t: Throwable) {
                Log.e("CrossCode", "[Steam] conflict resolve failed", t)
                msg = "Sync error — playing your local save."
                launch = dest.exists() && dest.length() > 0
            } finally { sync.disconnect() }
            runOnUiThread { setBusy(false, msg); showSyncStatus(); if (launch) launchGame() }
        }.apply { isDaemon = true; name = "SteamConflict"; start() }
    }

    // Downloads (or resumes, updates, repairs) CrossCode in GameDownloadService, so it carries on
    // when this screen is left. The service sets up the mod loader when it is done.
    private fun startDownload() {
        /* The progress notification needs the player's say-so on Android 13+. The download does not
         * wait for the answer and does not need it. */
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        GameDownloadService.start(this)
    }

    private fun notificationsOn(): Boolean =
        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

    // Mirrors the download service: the bar, the status line and the button.
    private fun showDownload() {
        if (GameDownloadState.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val percent = GameDownloadState.percent
            downloadBar.visibility = View.VISIBLE
            downloadBar.isIndeterminate = percent == null
            percent?.let { downloadBar.progress = it }
            status.text = GameDownloadState.status
            updatePlayLabel()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            downloadBar.visibility = View.GONE
            GameDownloadState.takeEnd()?.let { end ->
                if (prefs.getString("token", null) != null) status.text = end
                updatePlayLabel()
            }
        }
    }

    // A newer build is on Steam: update now, or play the installed one this time.
    private var updateDeclined = false
    private fun askUpdate() {
        AlertDialog.Builder(this)
            .setTitle("CrossCode has an update")
            .setMessage("Steam has build ${GameFiles.steamBuild(this)}; this device has build ${GameFiles.installedBuild(this)}.\n\n" +
                "Updating checks every file and downloads the ones that changed. The game cannot be played until the update has finished.")
            .setPositiveButton("Update now") { _, _ -> startDownload() }
            .setNegativeButton("Play this version") { _, _ -> updateDeclined = true; play() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun signOut() {
        GameDownloadService.cancel(this)
        prefs.edit().remove("account").remove("token").remove("guard")
            .remove("persona").remove("avatarHash").apply()
        runCatching { avatarFile.delete() }
        AchievementSync.reset(this)   // queued/synced achievements belong to the old account
        username.setText(""); password.setText("")
        renderState()
        setStatus("Signed out.")
    }

    private fun storeLogin(account: String, token: String, guard: String?) {
        prefs.edit().putString("account", account).putString("token", token).putString("guard", guard).apply()
    }

    private fun launchGame() {
        startActivity(Intent(this, GameActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    // Connect with a refresh token: fetch the profile (name + avatar) and download the cloud cc.save.
    private fun pullCloudSave(account: String, token: String): String {
        val (sync, connected) = SteamCloudSync.open(account, token)
        try {
            when (connected) {
                SteamCloudSync.ConnectResult.NETWORK_FAILED ->
                    return "Signed in. (Offline — your cloud save will sync once you're back online.)"
                SteamCloudSync.ConnectResult.AUTH_FAILED ->
                    return "Signed in, but Steam rejected the session. Try signing in again."
                SteamCloudSync.ConnectResult.OK -> { /* continue below */ }
            }
            runCatching { sync.fetchProfile()?.let { saveProfile(it) } }
            profileRefreshed = true
            val cloud = sync.findSave()
                ?: return "Signed in. No cloud save found yet — you're ready to play."
            val dest = File(getExternalFilesDir(null), "cloudsave/cc.save")
            return if (sync.pull(cloud.name, dest)) {
                lastSyncedSha = SteamCloudSync.sha1Hex(dest)
                "Cloud save downloaded. Ready to play!"
            } else "Signed in, but the save download failed. Tap PLAY to retry."
        } finally {
            sync.disconnect()
        }
    }

    // Download the Steam avatar JPG for this hash into profile/avatar.png (the name is historical;
    // the decoder reads the content, not the extension).
    private fun downloadAvatar(hex: String): Boolean {
        return try {
            val conn = URL("https://avatars.steamstatic.com/${hex}_full.jpg").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            val bytes = try {
                if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { it.readBytes() }
            } finally { conn.disconnect() }
            avatarFile.parentFile?.mkdirs()
            val tmp = File(avatarFile.parentFile, avatarFile.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(avatarFile)) { avatarFile.writeBytes(bytes); tmp.delete() }
            Log.i("CrossCode", "[Steam] avatar saved (${bytes.size} bytes)")
            true
        } catch (t: Throwable) {
            Log.e("CrossCode", "[Steam] avatar download failed", t); false
        }
    }

    private fun setStatus(text: String) = runOnUiThread { status.text = text }

    private fun setBusy(busy: Boolean, msg: String = "") = runOnUiThread {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        playButton.isEnabled = !busy
        signInButton.isEnabled = !busy
        qrButton.isEnabled = !busy
        if (msg.isNotEmpty()) status.text = msg
    }

    // Controller A button activates the focused control (D-pad/Center/Enter already do).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            currentFocus?.let { it.performClick(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Blocks the caller (Steam poll thread) until the user enters their Steam Guard code.
    private fun promptForGuardCode(previousWasWrong: Boolean): String {
        val latch = CountDownLatch(1)
        var code = ""
        runOnUiThread {
            val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_CLASS_TEXT }
            AlertDialog.Builder(this)
                .setTitle(if (previousWasWrong) "Code incorrect — try again" else "Steam Guard code")
                .setMessage("Enter the code from your Steam Mobile app or email (or approve in the app).")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> code = input.text.toString().trim(); latch.countDown() }
                .show()
        }
        latch.await()
        return code
    }

}
