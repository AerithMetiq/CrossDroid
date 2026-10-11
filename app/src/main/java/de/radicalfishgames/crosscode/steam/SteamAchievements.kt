package de.radicalfishgames.crosscode.steam

import android.util.Log
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientGetUserStats
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStats2
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse
import `in`.dragonbra.javasteam.steam.handlers.ClientMsgHandler
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.types.KeyValue
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Steam achievements for CrossCode over the CM connection SteamCloudSync already holds.
//
// JavaSteam 1.6.0's SteamUserStats only covers leaderboards, so this handler sends the user-stats
// messages itself. Achievements are not stored by name: the stats schema maps each achievement's
// API name to a bit inside a 32-bit "achievement block" stat. Unlocking = set that bit and store
// the stat back.
//
//   1. ClientGetUserStats      -> crc_stats, current stat values, and the schema (binary KeyValues)
//   2. schema: stats/<statId>/bits/<bit>/name = API name (e.g. the trophy's steamID in CrossCode)
//   3. ClientStoreUserStats2   -> {statId, value | (1 << bit)}, with the crc from step 1
//
// Steam only accepts stat writes while the account is "in" the game, so a live sync briefly
// reports CrossCode as running (notifyGamesPlayed) and clears it afterwards. A dry run never
// does that and never writes anything — it only fetches and logs.
class SteamAchievements : ClientMsgHandler() {

    data class Achievement(val apiName: String, val statId: Int, val bit: Int, val displayName: String?)

    data class UserStats(
        val crc: Int,
        val values: Map<Int, Int>,
        val schema: Map<String, Achievement>
    ) {
        fun isUnlocked(a: Achievement) = ((values[a.statId] ?: 0) and (1 shl a.bit)) != 0
    }

    data class SyncReport(
        val dryRun: Boolean,
        val alreadyUnlocked: Set<String>,   // Steam already had these
        val unlockedNow: Set<String>,       // written + verified this run (empty on a dry run)
        val wouldUnlock: Set<String>,       // dry run: what a live run would write
        val unknown: Set<String>,           // not in the schema (typo, or not a Steam achievement)
        val failed: Set<String>             // written but not confirmed by the re-fetch
    )

    @Volatile private var pendingStats: CompletableFuture<CMsgClientGetUserStatsResponse>? = null
    @Volatile private var pendingStore: CompletableFuture<CMsgClientStoreUserStatsResponse>? = null

    // Reconcile `apiNames` (achievements the game says are earned) with Steam.
    // Returns null if Steam didn't answer the stats request.
    fun sync(apiNames: Set<String>, dryRun: Boolean): SyncReport? {
        if (apiNames.isEmpty()) return SyncReport(dryRun, emptySet(), emptySet(), emptySet(), emptySet(), emptySet())

        if (!dryRun) setPlaying(true)
        try {
            val stats = getUserStats() ?: return null
            if (stats.schema.isEmpty()) {
                Log.e(TAG, "[Achievements] Steam returned no achievement schema — nothing to do")
                return null
            }

            if (dryRun) logSchema(stats)

            val unknown = apiNames.filterNot { it in stats.schema }.toSet()
            val wanted = apiNames.mapNotNull { stats.schema[it] }
            val already = wanted.filter { stats.isUnlocked(it) }.map { it.apiName }.toSet()
            val toUnlock = wanted.filterNot { stats.isUnlocked(it) }

            if (unknown.isNotEmpty()) Log.w(TAG, "[Achievements] not in Steam's schema, skipping: $unknown")
            Log.i(TAG, "[Achievements] earned in game=${apiNames.size}, already on Steam=${already.size}, " +
                    "missing on Steam=${toUnlock.size}${if (dryRun) " (DRY RUN — nothing will be written)" else ""}")
            toUnlock.forEach {
                Log.i(TAG, "[Achievements]   ${if (dryRun) "would unlock" else "unlocking"}: ${it.apiName} " +
                        "(${it.displayName ?: "?"}) -> stat ${it.statId} bit ${it.bit}")
            }

            if (dryRun || toUnlock.isEmpty()) {
                return SyncReport(dryRun, already, emptySet(), toUnlock.map { it.apiName }.toSet(), unknown, emptySet())
            }

            // Several achievements can share one block stat: OR all their bits into one value.
            val changes = HashMap<Int, Int>()
            for (a in toUnlock) {
                val base = changes[a.statId] ?: stats.values[a.statId] ?: 0
                changes[a.statId] = base or (1 shl a.bit)
            }
            val storeResult = store(stats.crc, changes)
            Log.i(TAG, "[Achievements] store result: $storeResult")

            // Don't trust the store reply alone (it may not arrive, or may report partial
            // validation failures) — re-fetch and check the bits actually stuck.
            val after = getUserStats()
            val confirmed = if (after == null) emptySet()
                            else toUnlock.filter { after.isUnlocked(it) }.map { it.apiName }.toSet()
            val failed = toUnlock.map { it.apiName }.toSet() - confirmed
            if (failed.isNotEmpty()) Log.e(TAG, "[Achievements] not confirmed after store: $failed")
            Log.i(TAG, "[Achievements] unlocked ${confirmed.size}: $confirmed")
            return SyncReport(false, already, confirmed, emptySet(), unknown, failed)
        } finally {
            if (!dryRun) setPlaying(false)
        }
    }

    fun getUserStats(timeoutSec: Long = 15): UserStats? {
        val me = client.steamID ?: return null
        val future = CompletableFuture<CMsgClientGetUserStatsResponse>()
        pendingStats = future

        val msg = ClientMsgProtobuf<CMsgClientGetUserStats.Builder>(
            CMsgClientGetUserStats::class.java, EMsg.ClientGetUserStats
        ).apply {
            sourceJobID = client.getNextJobID()
            body.gameId = SteamCloudSync.APP_ID.toLong()
            body.steamIdForUser = me.convertToUInt64()
            body.crcStats = 0
            body.schemaLocalVersion = -1   // no cached schema: ask Steam to include it
        }
        client.send(msg)

        val resp = try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            Log.e(TAG, "[Achievements] no reply to ClientGetUserStats within ${timeoutSec}s")
            return null
        } finally {
            pendingStats = null
        }

        val result = EResult.from(resp.eresult)
        if (result != EResult.OK) {
            Log.e(TAG, "[Achievements] ClientGetUserStats failed: $result")
            return null
        }
        val values = resp.statsList.associate { it.statId to it.statValue }
        val schema = if (resp.hasSchema()) parseSchema(resp.schema.toByteArray()) else emptyMap()
        Log.i(TAG, "[Achievements] stats: crc=${resp.crcStats} stats=${values.size} achievements in schema=${schema.size}")
        return UserStats(resp.crcStats, values, schema)
    }

    private fun store(crc: Int, changes: Map<Int, Int>, timeoutSec: Long = 15): EResult? {
        val me = client.steamID ?: return null
        val future = CompletableFuture<CMsgClientStoreUserStatsResponse>()
        pendingStore = future

        val msg = ClientMsgProtobuf<CMsgClientStoreUserStats2.Builder>(
            CMsgClientStoreUserStats2::class.java, EMsg.ClientStoreUserStats2
        ).apply {
            sourceJobID = client.getNextJobID()
            body.gameId = SteamCloudSync.APP_ID.toLong()
            body.settorSteamId = me.convertToUInt64()
            body.setteeSteamId = me.convertToUInt64()
            body.crcStats = crc
            body.explicitReset = false
            for ((statId, value) in changes) {
                body.addStats(CMsgClientStoreUserStats2.Stats.newBuilder().setStatId(statId).setStatValue(value))
            }
        }
        client.send(msg)

        return try {
            val resp = future.get(timeoutSec, TimeUnit.SECONDS)
            if (resp.statsFailedValidationCount > 0) {
                Log.w(TAG, "[Achievements] stats failed validation: " +
                        resp.statsFailedValidationList.joinToString { "${it.statId}->${it.revertedStatValue}" })
            }
            if (resp.statsOutOfDate) Log.w(TAG, "[Achievements] Steam says our stats were out of date")
            EResult.from(resp.eresult)
        } catch (e: TimeoutException) {
            Log.w(TAG, "[Achievements] no store reply within ${timeoutSec}s (will verify by re-fetching)")
            null
        } finally {
            pendingStore = null
        }
    }

    // Tell Steam we're running CrossCode (or nothing). Required for stat writes; also what makes
    // the profile show "In-game", so it's only held for the few seconds a live sync takes.
    private fun setPlaying(playing: Boolean) {
        val apps = client.getHandler(SteamApps::class.java) ?: return
        val me = client.steamID ?: return
        val games = if (playing) listOf(
            GamePlayedInfo(
                gameId = SteamCloudSync.APP_ID.toLong(),
                processId = android.os.Process.myPid(),
                ownerId = me.accountID.toInt(),
                gameBuildId = 0
            )
        ) else emptyList()
        runCatching { apps.notifyGamesPlayed(games, EOSType.AndroidUnknown) }
            .onFailure { Log.w(TAG, "[Achievements] notifyGamesPlayed($playing) failed", it) }
        if (playing) Thread.sleep(1500)   // give Steam a moment to register the session before writing
    }

    // Binary KeyValues: <appid> { stats { <statId> { type "4" bits { <n> { name "..." bit "n"
    //   display { name { english "..." } } } } } } }   (type 4 / ACHIEVEMENTS = achievement block)
    private fun parseSchema(bytes: ByteArray): Map<String, Achievement> {
        val root = KeyValue()
        val ok = runCatching { root.tryReadAsBinary(ByteArrayInputStream(bytes)) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "[Achievements] couldn't parse stats schema (${bytes.size} bytes)")
            return emptyMap()
        }
        // tryReadAsBinary may hand back the <appid> node itself or a wrapper around it.
        val statsNode = sequenceOf(root, root.children.firstOrNull())
            .filterNotNull()
            .map { it.get("stats") }
            .firstOrNull { it != KeyValue.INVALID }
            ?: run {
                Log.e(TAG, "[Achievements] schema has no 'stats' node (root='${root.name}')")
                return emptyMap()
            }

        val out = LinkedHashMap<String, Achievement>()
        for (stat in statsNode.children) {
            val statId = stat.name?.toIntOrNull() ?: continue
            val type = stat.get("type").value ?: ""
            val bits = stat.get("bits")
            val isBlock = type == "4" || type.equals("ACHIEVEMENTS", ignoreCase = true)
            if (!isBlock || bits == KeyValue.INVALID) continue
            for (bitNode in bits.children) {
                val bit = bitNode.get("bit").value?.toIntOrNull() ?: bitNode.name?.toIntOrNull() ?: continue
                val name = bitNode.get("name").value ?: continue
                val display = bitNode.get("display").get("name").let { n ->
                    n.get("english").value ?: n.value
                }
                out[name] = Achievement(name, statId, bit, display)
            }
        }
        return out
    }

    private fun logSchema(stats: UserStats) {
        Log.i(TAG, "[Achievements] --- Steam schema for app ${SteamCloudSync.APP_ID} (${stats.schema.size}) ---")
        for (a in stats.schema.values) {
            Log.i(TAG, "[Achievements]   ${if (stats.isUnlocked(a)) "[x]" else "[ ]"} ${a.apiName} " +
                    "(${a.displayName ?: "?"}) stat ${a.statId} bit ${a.bit}")
        }
    }

    override fun handleMsg(packetMsg: IPacketMsg) {
        when (packetMsg.msgType) {
            EMsg.ClientGetUserStatsResponse -> {
                val body = ClientMsgProtobuf<CMsgClientGetUserStatsResponse.Builder>(
                    CMsgClientGetUserStatsResponse::class.java, packetMsg
                ).body.build()
                if (body.gameId == SteamCloudSync.APP_ID.toLong()) pendingStats?.complete(body)
            }
            EMsg.ClientStoreUserStatsResponse -> {
                val body = ClientMsgProtobuf<CMsgClientStoreUserStatsResponse.Builder>(
                    CMsgClientStoreUserStatsResponse::class.java, packetMsg
                ).body.build()
                if (body.gameId == SteamCloudSync.APP_ID.toLong()) pendingStore?.complete(body)
            }
            else -> {}
        }
    }

    companion object {
        private const val TAG = "CrossCode"
    }
}
