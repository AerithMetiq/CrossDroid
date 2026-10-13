package de.radicalfishgames.crosscode.preferences

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import de.radicalfishgames.crosscode.FrameRate
import de.radicalfishgames.crosscode.GameDownloadService
import de.radicalfishgames.crosscode.GameDownloadState
import de.radicalfishgames.crosscode.GameFiles
import de.radicalfishgames.crosscode.R
import de.radicalfishgames.crosscode.steam.SaveBackups
import de.radicalfishgames.crosscode.steam.SaveSync
import de.radicalfishgames.crosscode.steam.SteamCloudSync
import java.io.*
import java.lang.StringBuilder


class PreferencesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.actvitity_preferences)

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.preferences_placeholder, PreferenceFragment())
            .commit()

    }
}

// For whatever reason AndroidX _requires_ us to use a fragment, even though it could just have the whole activity
class PreferenceFragment : PreferenceFragmentCompat() {
    companion object {
        const val FILE_PICKER_REQUEST_CODE = 10
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        val filePicker = findPreference("fileToImport") as Preference?
        filePicker!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/plain"
            startActivityForResult(intent, FILE_PICKER_REQUEST_CODE)
            true
        }

        setUpFrameRate()
        onClick("cloud_check") { checkSave() }
        onClick("save_restore") { restoreBackup() }
        onClick("game_verify") { verifyGame() }
        onClick("game_delete") { deleteGame() }
        describeGameFiles()
    }

    private fun onClick(key: String, action: () -> Unit) {
        findPreference<Preference>(key)?.onPreferenceClickListener = Preference.OnPreferenceClickListener { action(); true }
    }

    private fun message(title: String, text: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    // The limits offered are the ones this screen can show (a 60 Hz screen is not offered 90 or 120).
    private fun setUpFrameRate() {
        val list = findPreference<ListPreference>("fps_limit") ?: return
        val rates = try {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= 30) requireActivity().display else requireActivity().windowManager.defaultDisplay
            if (Build.VERSION.SDK_INT < 23 || display == null) emptyList()
            else display.supportedModes
                .filter { it.physicalWidth == display.mode.physicalWidth && it.physicalHeight == display.mode.physicalHeight }
                .map { Math.round(it.refreshRate) }.distinct()
        } catch (e: Exception) { emptyList<Int>() }
        val choices = listOf(FrameRate.OFF) + FrameRate.choicesFor(rates)
        list.entries = choices.map { FrameRate.label(it) }.toTypedArray()
        list.entryValues = choices.map { it.toString() }.toTypedArray()
        val stored = FrameRate.normalize(GameFiles.fpsLimit(requireContext()), choices.drop(1))
        list.value = stored.toString()
        list.summaryProvider = Preference.SummaryProvider<ListPreference> { p ->
            "${p.entry ?: "Off"} · " + getString(R.string.fps_limit_summary)
        }
    }

    // The dry run: logs on, compares the save on this device with Steam Cloud's, and says what PLAY
    // would do. Read-only on both sides.
    private fun checkSave() {
        val ctx = requireContext().applicationContext
        val prefs = GameFiles.steamPrefs(ctx)
        val account = prefs.getString("account", null)
        val token = prefs.getString("token", null)
        if (account == null || token == null) { message("Check the save", "Sign in on the launcher first."); return }
        val wait = AlertDialog.Builder(requireContext()).setMessage("Asking Steam…").setCancelable(false).show()
        Thread {
            val text = try {
                val (sync, result) = SteamCloudSync.open(account, token)
                try {
                    when (result) {
                        SteamCloudSync.ConnectResult.NETWORK_FAILED -> "Couldn't reach Steam. Nothing was changed."
                        SteamCloudSync.ConnectResult.AUTH_FAILED -> "Steam rejected the saved login. Sign in again on the launcher."
                        SteamCloudSync.ConnectResult.OK -> {
                            val file = GameFiles.saveFile(ctx)
                            val cloud = sync.findSave()
                            SaveSync.report(
                                SaveSync.Side(SteamCloudSync.sha1Hex(file), file.length(), file.lastModified()),
                                SaveSync.Side(cloud?.shaHex?.takeIf { it.isNotEmpty() }, (cloud?.size ?: 0).toLong(), cloud?.timestampMillis ?: 0),
                                GameFiles.lastSyncedSha(ctx),
                                GameFiles.cloudUploads(ctx),
                            ) + "\n\nNothing was changed, here or on Steam."
                        }
                    }
                } finally { sync.disconnect() }
            } catch (t: Throwable) {
                "The check failed: ${t.message ?: t.javaClass.simpleName}. Nothing was changed."
            }
            activity?.runOnUiThread {
                runCatching { wait.dismiss() }
                message("Check the save", text)
            }
        }.apply { isDaemon = true; name = "SaveCheck"; start() }
    }

    // Lists the save backups, newest first, and puts the chosen one back after a confirmation.
    private fun restoreBackup() {
        val ctx = requireContext()
        val save = GameFiles.saveFile(ctx)
        val backups = SaveBackups.list(save)
        if (backups.isEmpty()) { message("Restore a save backup", "There are no save backups on this device yet."); return }
        val labels = backups.map {
            "${it.label}  ·  ${it.bytes / 1024} KiB" + (if (it.name.contains("cloud")) "  ·  from Steam Cloud" else "")
        }
        AlertDialog.Builder(ctx)
            .setTitle("Restore which backup?")
            .setItems(labels.toTypedArray()) { _, which ->
                val chosen = backups[which]
                AlertDialog.Builder(ctx)
                    .setTitle("Restore the backup of ${chosen.label}?")
                    .setMessage("It replaces the save on this device. The save it replaces is backed up first, so this can be undone. " +
                        "At the next PLAY the sync treats it as changed on this device.")
                    .setPositiveButton("Restore") { _, _ ->
                        val ok = runCatching { SaveBackups.restore(save, chosen.name) }.getOrDefault(false)
                        message("Restore a save backup", if (ok) "Restored the save of ${chosen.label}." else "Could not restore that backup.")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // The download checks every file against Steam's list when asked to verify, and fetches what is
    // missing or different. It runs in the background; the launcher shows its progress.
    private fun verifyGame() {
        val ctx = requireContext()
        if (GameDownloadState.running) { message("Verify game files", "A download is already running. See the launcher."); return }
        if (GameFiles.steamPrefs(ctx).getString("token", null) == null) { message("Verify game files", "Sign in on the launcher first."); return }
        GameDownloadService.start(ctx, verify = true)
        activity?.finish()
    }

    private fun deleteGame() {
        val ctx = requireContext()
        if (GameDownloadState.running) { message("Delete game files", "A download is running. Cancel it on the launcher first."); return }
        AlertDialog.Builder(ctx)
            .setTitle("Delete the game files?")
            .setMessage("This frees the storage CrossCode takes, including the mod loader and any mods you added to it. " +
                "Your save, its backups and your settings stay. You can download the game again at any time.")
            .setPositiveButton("Delete") { _, _ ->
                val app = ctx.applicationContext
                findPreference<Preference>("game_delete")?.summary = "Deleting…"
                Thread {
                    GameFiles.deleteGame(app)
                    activity?.runOnUiThread { describeGameFiles() }
                }.apply { name = "GameDelete"; start() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Says how much storage the game takes (measured off the main thread).
    private fun describeGameFiles() {
        val pref = findPreference<Preference>("game_delete") ?: return
        val app = requireContext().applicationContext
        Thread {
            val exists = GameFiles.gameDir(app).exists()
            val mib = if (exists) GameFiles.gameBytes(app) / 1048576 else 0
            val build = GameFiles.installedBuild(app)
            activity?.runOnUiThread {
                pref.summary = if (!exists) "CrossCode is not downloaded."
                else "CrossCode takes $mib MiB" + (if (build != null) " (build $build). " else ". ") + getString(R.string.game_delete_summary)
            }
        }.apply { isDaemon = true; name = "GameSize"; start() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(requestCode == FILE_PICKER_REQUEST_CODE) {
            val preferences: SharedPreferences =
                requireContext().getSharedPreferences("de.radicalfishgames.crosscode_preferences", Context.MODE_PRIVATE)

            val contentDescriber: Uri? = data?.data
            var reader: BufferedReader? = null
            try {
                // open the user-picked file for reading:
                val inputStream: InputStream? =
                    contentDescriber?.let { activity!!.contentResolver.openInputStream(it) }
                // now read the content:
                reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                val builder = StringBuilder()
                while (reader.readLine().also { line = it } != null) {
                    builder.append(line)
                }
                // Do something with the content in
                preferences.edit()
                    .putString("save_to_import", builder.toString())
                    .apply()
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                if (reader != null) {
                    try {
                        reader.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

}