package de.radicalfishgames.crosscode.steam

import android.content.Context
import android.util.Log

// Bookkeeping between the game and Steam:
//   pending = achievement API names the game reported as earned that aren't known to be on Steam yet
//   synced  = names confirmed on Steam (so the per-boot backfill doesn't re-send them forever)
// Persisted in SharedPreferences, so an unlock earned offline (or before a crash) survives until the
// next online quit/PLAY flushes it.
object AchievementSync {

    private const val TAG = "CrossCode"
    private const val STEAM_PREFS = "steam"
    private const val KEY_PENDING = "achPending"
    private const val KEY_SYNCED = "achSynced"

    const val PREF_ENABLED = "steam_achievements"
    const val PREF_DRY_RUN = "steam_achievements_dry_run"
    private const val GAME_PREFS = "de.radicalfishgames.crosscode_preferences"

    fun isEnabled(ctx: Context) = gamePrefs(ctx).getBoolean(PREF_ENABLED, true)
    fun isDryRun(ctx: Context) = gamePrefs(ctx).getBoolean(PREF_DRY_RUN, true)

    @Synchronized
    fun add(ctx: Context, names: Collection<String>) {
        val p = steamPrefs(ctx)
        val synced = p.getStringSet(KEY_SYNCED, emptySet())!!
        val pending = p.getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
        val fresh = names.filter { it.isNotBlank() && it !in synced && it !in pending }
        if (fresh.isEmpty()) return
        pending += fresh
        p.edit().putStringSet(KEY_PENDING, pending).apply()
        Log.i(TAG, "[Achievements] queued: $fresh")
    }

    @Synchronized
    fun pending(ctx: Context): Set<String> = steamPrefs(ctx).getStringSet(KEY_PENDING, emptySet())!!.toSet()

    @Synchronized
    private fun resolve(ctx: Context, synced: Set<String>, dropped: Set<String>) {
        val p = steamPrefs(ctx)
        val pending = p.getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()
        val all = p.getStringSet(KEY_SYNCED, emptySet())!!.toMutableSet()
        pending -= synced; pending -= dropped
        all += synced
        p.edit().putStringSet(KEY_PENDING, pending).putStringSet(KEY_SYNCED, all).apply()
    }

    // Forget everything (e.g. on sign-out: another account has its own achievements).
    @Synchronized
    fun reset(ctx: Context) {
        steamPrefs(ctx).edit().remove(KEY_PENDING).remove(KEY_SYNCED).apply()
    }

    // Push pending achievements over an already-connected SteamCloudSync. Never throws.
    // Returns a short user-facing note when something was unlocked, else null.
    fun flush(ctx: Context, sync: SteamCloudSync): String? {
        if (!isEnabled(ctx)) return null
        val pending = pending(ctx)
        if (pending.isEmpty()) return null
        val dryRun = isDryRun(ctx)

        val report = runCatching { sync.achievements.sync(pending, dryRun) }
            .onFailure { Log.e(TAG, "[Achievements] sync failed", it) }
            .getOrNull() ?: return null

        // Dry run: only record what Steam already has (harmless bookkeeping). Keep the rest pending
        // so the next run logs them again. Unknown names are dropped either way.
        val synced = if (dryRun) report.alreadyUnlocked else report.alreadyUnlocked + report.unlockedNow
        resolve(ctx, synced, report.unknown)

        return when {
            report.unlockedNow.size == 1 -> "Unlocked 1 Steam achievement."
            report.unlockedNow.size > 1 -> "Unlocked ${report.unlockedNow.size} Steam achievements."
            else -> null
        }
    }

    private fun steamPrefs(ctx: Context) = ctx.getSharedPreferences(STEAM_PREFS, Context.MODE_PRIVATE)
    private fun gamePrefs(ctx: Context) = ctx.getSharedPreferences(GAME_PREFS, Context.MODE_PRIVATE)
}
