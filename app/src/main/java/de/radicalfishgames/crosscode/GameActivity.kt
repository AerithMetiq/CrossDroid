package de.radicalfishgames.crosscode

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.radicalfishgames.crosscode.features.*
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager
import de.radicalfishgames.crosscode.steam.AchievementSync
import de.radicalfishgames.crosscode.steam.SteamCloudSync
import de.radicalfishgames.crosscode.steam.SteamPresence
import org.json.JSONTokener
import java.io.File


class GameActivity : AppCompatActivity() {

    val errorHandler = ErrorHandler(this)

    private lateinit var gameWrapper: GameWrapper

    // This SharedPreferences files is the one the preference activity automatically generates
    val preferences: SharedPreferences
         get() = getSharedPreferences("de.radicalfishgames.crosscode_preferences", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_game)

        startGame()
    }

    private fun startGame(){
        Thread.setDefaultUncaughtExceptionHandler(errorHandler)

        val nativeControllerActive = isNativeControllerActive()
        Log.d("CrossCode", "Native controller found: $nativeControllerActive")


        val dataFilesPath = this.getExternalFilesDir(null)
        if (dataFilesPath == null) {
            Toast.makeText(this@GameActivity, "Storage permission denied", Toast.LENGTH_LONG).show()
            return
        }
        val gameDir = "${dataFilesPath.absolutePath}/CrossCode"
        Log.d("CrossCode", "Game directory: $gameDir")

        var gameEntryPoint = "assets/node-webkit.html"
        val modLoaderEntryFile = (File("$gameDir/ccloader/index.html") or File("$gameDir/ccloader/main.html")) or File("$gameDir/ccloader3/main.html")
        var modLoaderPresent = false
        if(modLoaderEntryFile.exists()){
            Log.d("CrossCode", "Modloader found!")
            gameEntryPoint = modLoaderEntryFile.canonicalPath.replace(Regex("(.*:\\d+/)"), "")
            modLoaderPresent = true
        }

        Log.d("CrossCode", "Game entry point: $gameEntryPoint Attempting to load.")


        gameWrapper = GameWrapper(findViewById<android.webkit.WebView>(R.id.game_view), modLoaderPresent, gameDir)
        gameWrapper.initWebView()

        // Order is important, since some of these depend on one another and this is also the initialization order
        gameWrapper.features.addAll(listOf(
            ModListProvider(gameWrapper, this),
            ExtensionLoadingFix(gameWrapper, this),
            GameEventManager(gameWrapper, this),
            VirtualControllerFeature(gameWrapper, this),
            AutoLayoutSwitchFeature(gameWrapper, this),
            CutsceneLayoutDisableFeature(gameWrapper, this),
            OverlayTransparencyFeature(gameWrapper, this),
            CloudSaveFeature(gameWrapper, this),
            QuitMenuFeature(gameWrapper, this),
            ImportExportFeature(gameWrapper, this),
            HapticFeedbackFeature(gameWrapper, this),
            OverlayScaleFeature(gameWrapper, this),
            SteamAchievementFeature(gameWrapper, this),
            FrameRateFeature(gameWrapper, this)
        ))
        applyRefreshRate()

        // Bridge the in-game "Exit" button (added by QuitMenuFeature) to the cloud-save quit flow.
        gameWrapper.exposeJSInterface(QuitBridge(), "CCAndroid")

        gameWrapper.loadGame(gameEntryPoint)

    }

    // The refresh rates this screen offers at the size it is running at now, and the frame-rate
    // limits offered for them (a 60 Hz screen is not offered 90 or 120).
    val refreshRates: List<Int> by lazy {
        try {
            @Suppress("DEPRECATION")
            val display = if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            if (android.os.Build.VERSION.SDK_INT < 23 || display == null) emptyList()
            else {
                val now = display.mode
                display.supportedModes
                    .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                    .map { Math.round(it.refreshRate) }.distinct().sorted()
            }
        } catch (e: Exception) { emptyList() }
    }
    val frameRateChoices: List<Int> by lazy { FrameRate.choicesFor(refreshRates) }

    // Asks for the screen refresh rate the frame-rate limit is best shown at (see FrameRate.refreshFor).
    private fun applyRefreshRate() {
        if (refreshRates.size < 2) return
        val limit = FrameRate.normalize(GameFiles.fpsLimit(this), frameRateChoices)
        val rate = FrameRate.refreshFor(limit, refreshRates) ?: return
        window.attributes = window.attributes.apply { preferredRefreshRate = rate.toFloat() }
        Log.i("CrossCode", "[FrameRate] screen asked for $rate Hz of $refreshRates")
    }

    // The in-game menu (see PauseMenuView): a panel on the right, added over the game on first use.
    private var menu: PauseMenuView? = null
    // The direction a stick or hat is held in while the menu is open, as a D-pad key code, or 0.
    private var menuHeldDirection = 0
    private var lastBackAt = 0L
    private var lastButtonBAt = 0L

    // Back opens the menu, and closes it when it is open. The system back gesture/button always
    // offers a way out (essential on handhelds like the AYN Thor, where a native controller is
    // always present so the old code blocked back entirely).
    //
    // The game is paused while the menu is open, the same way it is when the app goes to the
    // background (the page is told it lost focus), and the controller drives the menu instead.
    override fun onBackPressed() = backPressed()

    // One Back press, from whichever path delivered it (the key, or the back gesture); a second
    // report of the same press is dropped.
    private fun backPressed() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackAt < 300) return
        /* A controller's B that nothing handled is turned into a Back by the system, right after
         * the B itself. The game reads B through the gamepad API, so that Back is not a Back.
         * (Measured on the Retroid Pocket Nova: it does not arrive marked as a fallback key, so the
         * timing is what tells it apart; with the old source test the real Back button was thrown
         * away as well, because there it belongs to the built-in gamepad.) */
        if (now - lastButtonBAt < 300) return
        lastBackAt = now
        if (menu?.isOpen == true) closeMenu() else openMenu()
    }

    private fun openMenu() {
        val view = menu ?: PauseMenuView(this).also { m ->
            m.onTouchControls = { setTouchControls(it) }
            m.onFrameRate = {
                gameWrapper.getFeature(FrameRateFeature::class).set(it)
                applyRefreshRate()
            }
            m.onResume = { closeMenu() }
            m.onExit = { closeMenu(resume = false); quitWithCloudSync() }
            findViewById<ViewGroup>(R.id.root_view).addView(
                m, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            menu = m
        }
        val touch = gameWrapper.getFeature(VirtualControllerFeature::class)
        view.setTouchControls(
            GameFiles.touchControls(this) && (touch.shown || !touch.enabled && preferences.getBoolean("force_virtual", false))
        )
        view.setFrameRate(gameWrapper.getFeature(FrameRateFeature::class).limit, frameRateChoices)
        menuHeldDirection = 0
        view.open()
        gameWrapper.runJS("window.dispatchEvent(new FocusEvent(\"blur\"));")
    }

    private fun closeMenu(resume: Boolean = true) {
        menu?.close()
        if (resume) {
            gameWrapper.runJS("window.dispatchEvent(new FocusEvent(\"focus\"));")
            goIntoFullScreen()
        }
    }

    // While the menu is open the controller is the menu's: D-pad and A go to it, B resumes (on
    // release, so the game does not see B held as it comes back).
    private fun menuKey(event: KeyEvent): Boolean {
        val view = menu?.takeIf { it.isOpen } ?: return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) closeMenu()
            return true
        }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN) view.padKey(code)
                true
            }
            else -> isGamepad(event.source)     // nothing else from the controller reaches the game
        }
    }

    private fun isGamepad(source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) lastButtonBAt = android.os.SystemClock.uptimeMillis()
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // A real Back key is acted on whatever device sent it; a Back the system made out of an
            // unhandled B is not (see backPressed).
            if ((event.flags and KeyEvent.FLAG_FALLBACK) != 0) return true
            if (event.action == KeyEvent.ACTION_UP) backPressed()
            return true
        }
        if (menuKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // A stick or hat while the menu is open: pushing it in a direction is one press that way.
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val view = menu?.takeIf { it.isOpen } ?: return super.dispatchGenericMotionEvent(event)
        if (!isGamepad(event.source)) return super.dispatchGenericMotionEvent(event)
        fun axis(a: Int, b: Int): Float {
            val x = event.getAxisValue(a)
            val y = event.getAxisValue(b)
            return if (kotlin.math.abs(x) >= kotlin.math.abs(y)) x else y
        }
        val x = axis(MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_X)
        val y = axis(MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_Y)
        val direction = when {
            y <= -0.6f -> KeyEvent.KEYCODE_DPAD_UP
            y >= 0.6f -> KeyEvent.KEYCODE_DPAD_DOWN
            x <= -0.6f -> KeyEvent.KEYCODE_DPAD_LEFT
            x >= 0.6f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> 0
        }
        if (direction != menuHeldDirection) {
            menuHeldDirection = direction
            if (direction != 0) view.padKey(direction)
        }
        return true
    }

    // The pause menu's switch: stores the setting, and shows or hides the overlay now when it can.
    private fun setTouchControls(on: Boolean) {
        val edit = preferences.edit().putBoolean("touch_controls", on)
        /* With a controller connected the overlay only appears when it is forced; asking for touch
         * controls on such a device is asking for exactly that. */
        if (on && isNativeControllerActive()) edit.putBoolean("force_virtual", true)
        edit.apply()
        if (!gameWrapper.getFeature(VirtualControllerFeature::class).show(on)) {
            Toast.makeText(this, "Touch controls will be ${if (on) "on" else "off"} the next time the game starts.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        /* Optional (Settings): show the account as playing on Steam while the game is on screen. */
        SteamPresence.start(this)
    }

    override fun onStop() {
        SteamPresence.stop()
        super.onStop()
    }

    // On quit: pull the current save out of the WebView, upload it to Steam Cloud, then return to
    // the launcher. (Last-write-wins for now; timestamp-based conflict resolution is a follow-up.)
    private fun quitWithCloudSync() {
        val prefs = getSharedPreferences("steam", Context.MODE_PRIVATE)
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        val saveFile = File(getExternalFilesDir(null), "cloudsave/cc.save")

        // Queue every trophy the game says is earned (callbacks run in order, so this lands before
        // the save callback below starts the upload thread that flushes the queue).
        gameWrapper.getFeature(SteamAchievementFeature::class).collectUnlocked()

        gameWrapper.runJS("localStorage.getItem('cc.save')", ValueCallback { result ->
            try {
                if (result != null && result != "null") {
                    (JSONTokener(result).nextValue() as? String)?.let {
                        saveFile.parentFile?.mkdirs()
                        saveFile.writeText(it)
                    }
                }
            } catch (e: Exception) {
                Log.e("CrossCode", "[Steam] quit: extracting save failed", e)
            }

            val offlineSession = prefs.getBoolean("sessionOffline", false)
            SteamPresence.stop()
            if (offlineSession || account == null || token == null || !saveFile.exists() || saveFile.length() == 0L) {
                // Offline this session (manual toggle, or couldn't reach Steam at launch), not signed
                // in, or nothing to push: keep the save local and DON'T attempt the cloud. The next
                // online PLAY reconciles it via lastSyncedSha (local-changed -> pushed up).
                if (offlineSession && saveFile.exists() && saveFile.length() > 0L) {
                    Toast.makeText(this, "Saved locally — will sync to Steam Cloud next time you're online.", Toast.LENGTH_SHORT).show()
                }
                returnToLauncher()
                return@ValueCallback
            }

            val progress = androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Saving your progress to Steam Cloud…")
                .setCancelable(false)
                .show()

            Thread {
                var msg = "Progress saved to Steam Cloud."
                try {
                    val (sync, connected) = SteamCloudSync.open(account, token)
                    try {
                        if (connected == SteamCloudSync.ConnectResult.OK) {
                            val name = sync.findSave()?.name ?: "%WinAppDataLocal%cc.save"
                            msg = if (!GameFiles.cloudUploads(this)) {
                                // Uploads are off (Settings): the save stays on this device. The next
                                // online PLAY sees it as changed here and says so.
                                "Saved on this device. Uploads to Steam Cloud are off."
                            } else when (sync.push(name, saveFile)) {
                                SteamCloudSync.PushResult.UPLOADED, SteamCloudSync.PushResult.UNCHANGED -> {
                                    prefs.edit().putString("lastSyncedSha", SteamCloudSync.sha1Hex(saveFile))
                                        .putLong("lastSyncAt", System.currentTimeMillis()).apply()
                                    "Progress saved to Steam Cloud."
                                }
                                SteamCloudSync.PushResult.REFUSED_SMALLER ->
                                    "Your cloud save looks more complete — saved this run locally and left the cloud untouched."
                                SteamCloudSync.PushResult.FAILED ->
                                    "Couldn't upload to Steam Cloud — saved locally, will retry next time."
                            }
                            // Same session: unlock any achievements earned this run. Never throws;
                            // anything that doesn't go through stays queued for next time.
                            AchievementSync.flush(this, sync)?.let { msg += " $it" }
                        } else {
                            msg = "Couldn't reach Steam Cloud — saved locally, will retry next time."
                        }
                    } finally {
                        sync.disconnect()
                    }
                } catch (t: Throwable) {
                    Log.e("CrossCode", "[Steam] quit-push failed", t)
                    msg = "Cloud save failed — your progress is saved locally."
                }
                runOnUiThread {
                    try { progress.dismiss() } catch (_: Throwable) {}
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    returnToLauncher()
                }
            }.apply { isDaemon = true; name = "QuitPush"; start() }
        })
    }

    private fun returnToLauncher() {
        startActivity(Intent(this, SteamLoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    // Exposed to the game's JS as window.CCAndroid; called by the title-menu "Exit" button.
    inner class QuitBridge {
        @JavascriptInterface
        fun quit() {
            runOnUiThread { quitWithCloudSync() }
        }
    }

    fun isNativeControllerActive(): Boolean {
        Log.d("CrossCode", "Searching for native controller...")

        InputDevice.getDeviceIds().forEach { deviceId ->
            val device = InputDevice.getDevice(deviceId) ?: return@forEach

            if(isController(device)){
                Log.d("CrossCode", "Found device with gamepad and joystick support: ${device.name}")
                Log.v("CrossCode", device.toString())
                return true
            }
        }

        return false
    }

    private fun isController(device: InputDevice): Boolean {
        // SOURCE_DPAD is apparently not how a gamepad is labelled, despite having a DPad available
        val hasControllerSources = device.supportsSource(InputDevice.SOURCE_GAMEPAD) && device.supportsSource(InputDevice.SOURCE_JOYSTICK)

        if(hasControllerSources && device.name.equals("uinput-fpc")) {
            Log.w("CrossCode", "Ignoring input device uinput-fpc as it's known to be a fingerprint scanner disguising as a controller: https://github.com/libgdx/gdx-controllers/issues/9")
            return false
        }

        return hasControllerSources
    }

    override fun onPause() {
        super.onPause()

        Log.d("CrossCode", "Pausing game.")

        gameWrapper.onPause()
    }

    override fun onResume() {
        super.onResume()

        Log.d("CrossCode", "Resuming game.")

        /* Coming back with the menu open: the game stays paused until the menu is closed. */
        if (menu?.isOpen != true) gameWrapper.onResume()

        goIntoFullScreen()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        gameWrapper.getFeature(ImportExportFeature::class)
            .onActivityResult(requestCode, resultCode, data)

        super.onActivityResult(requestCode, resultCode, data)
    }

    fun goIntoFullScreen(){
        runOnUiThread {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }
}
