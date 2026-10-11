package de.radicalfishgames.crosscode.features

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import de.radicalfishgames.crosscode.steam.AchievementSync
import org.json.JSONArray
import org.json.JSONTokener

// Routes CrossCode's Steam achievement calls to Steam on Android.
//
// The game's ig.Greenworks addon (impact.feature.greenworks.greenworks) only loads the native
// Greenworks module when ig.platform == DESKTOP, and every caller is gated on isActive():
//
//   sc.TrophyManager._notifyNewTrophy:   ig.greenworks.isActive() && t.steamID && ig.greenworks.activateAchievement(t.steamID)
//   sc.TrophyManager.onStorageGlobalLoad: the same, for EVERY trophy already in the save, on every boot
//
// So on Android nothing reaches Steam. We inject into ig.Greenworks so isActive() is true and
// activateAchievement(steamID) forwards to window.CCSteam.unlock(), which queues the name for the
// next Steam session (AchievementSync). The per-boot onStorageGlobalLoad pass doubles as a backfill
// of anything earned before this existed; already-synced names are skipped by the queue.
// isActive() is only consulted by the trophy manager, so flipping it has no other effect.
class SteamAchievementFeature(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    private val enabled get() = AchievementSync.isEnabled(hostActivity)

    override fun onPreGamePageLoad() {
        // Must be registered before loadUrl for the page to see window.CCSteam.
        gameWrapper.exposeJSInterface(Bridge(), "CCSteam")
    }

    // Patch as early as possible so it's in place before the save's globals load (the backfill pass);
    // repeated after load as a fallback. The JS is idempotent.
    override fun onGamePageStarted() { if (enabled) runJS(PATCH_JS) }
    override fun onPostGamePageLoad() { if (enabled) runJS(PATCH_JS) }

    // Read every earned trophy's steamID straight from the running game and queue them.
    // Called on quit as a catch-all in case an unlock call was missed (e.g. patch landed late).
    fun collectUnlocked() {
        if (!enabled) return
        runJS(SWEEP_JS, ValueCallback { result ->
            try {
                val json = JSONTokener(result ?: return@ValueCallback).nextValue() as? String ?: return@ValueCallback
                val arr = JSONArray(json)
                val names = (0 until arr.length()).map { arr.getString(it) }
                Log.i(TAG, "[Achievements] game reports ${names.size} earned trophies with a Steam ID")
                AchievementSync.add(hostActivity, names)
            } catch (e: Exception) {
                Log.e(TAG, "[Achievements] sweep failed", e)
            }
        })
    }

    inner class Bridge {
        @JavascriptInterface
        fun unlock(steamId: String) {
            if (enabled) AchievementSync.add(hostActivity, listOf(steamId))
        }
    }

    companion object {
        private const val TAG = "CrossCode"

        private val PATCH_JS = """
            (function () {
              if (window.__ccSteamAchPatched) return;
              window.__ccSteamAchPatched = true;
              function patch() {
                if (typeof ig === 'undefined' || !ig.Greenworks) { setTimeout(patch, 20); return; }
                if (!window.CCSteam || ig.platform === ig.PLATFORM_TYPES.DESKTOP) return;
                ig.Greenworks.inject({
                  isActive: function () { return true; },
                  activateAchievement: function (id, clearAfter) {
                    if (clearAfter) return;   // debug-only "unlock then clear" path: never forward
                    try { window.CCSteam.unlock(String(id)); } catch (e) { console.error("[cc-ach]", e); }
                  },
                  clearAchievement: function () {}
                });
                console.log("[cc-ach] ig.Greenworks bridged to Steam");
              }
              patch();
            })();
        """.trimIndent()

        private val SWEEP_JS = """
            (function () {
              try {
                var t = sc.trophies.trophies, out = [];
                for (var k in t) if (t[k].triggered && t[k].steamID) out.push(String(t[k].steamID));
                return JSON.stringify(out);
              } catch (e) { return "[]"; }
            })();
        """.trimIndent()
    }
}
