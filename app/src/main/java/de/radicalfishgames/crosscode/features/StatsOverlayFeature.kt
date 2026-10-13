package de.radicalfishgames.crosscode.features

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.webkit.JavascriptInterface
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import java.util.Locale

// The readout in the top-left corner, as in Alabaster Droid: frames per second, the size the game
// renders at, battery level and temperature, and the device's thermal state, e.g.
//   60 fps · 1136x640 · bat 50% 27.0°C · therm none
// Switched in Settings and in the in-game menu ("Show FPS / battery / temp").
//
// It is a layer of its own on the page, so it costs the game's renderer nothing. The frames counted
// are the engine's own (calls of ig.system.run), so the number is what the game draws, frame-rate
// limit included, and it reads 0 while the game is paused.
class StatsOverlayFeature(gameWrapper: GameWrapper, hostActivity: GameActivity) : Feature(gameWrapper, hostActivity) {

    var enabled: Boolean = hostActivity.preferences.getBoolean(PREF, false)
        private set

    override fun onPreGamePageLoad() {
        gameWrapper.exposeJSInterface(Telemetry(), "CCStats")
    }

    override fun onPostGamePageLoad() {
        runJS(OVERLAY_JS.replace("__ON__", enabled.toString()))
    }

    // Shows or hides the readout now and remembers the choice.
    fun set(on: Boolean) {
        enabled = on
        hostActivity.preferences.edit().putBoolean(PREF, on).apply()
        runJS("if(window.__ccStats){window.__ccStats.show($on);}")
    }

    // Read by the page every couple of seconds while the readout is shown.
    inner class Telemetry {
        @JavascriptInterface
        fun read(): String {
            val battery = hostActivity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            val percent = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "?"
            val temp = if (tenths != Int.MIN_VALUE) String.format(Locale.US, " %.1f°C", tenths / 10.0) else ""
            return "bat $percent$temp · therm ${thermal()}"
        }

        private fun thermal(): String {
            if (Build.VERSION.SDK_INT < 29) return "?"
            val power = hostActivity.getSystemService(Context.POWER_SERVICE) as PowerManager
            return when (power.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "?"
            }
        }
    }

    companion object {
        const val PREF = "stats_overlay"

        private val OVERLAY_JS = """
            (function () {
              if (window.__ccStats) { window.__ccStats.show(__ON__); return; }
              var box = document.createElement('div');
              box.style.cssText = 'position:fixed;left:14px;top:10px;z-index:2147483647;pointer-events:none;' +
                'font:13px monospace;color:#e8dcc8;text-shadow:0 0 3px #000,0 0 3px #000;white-space:nowrap;display:none';
              (document.body || document.documentElement).appendChild(box);
              var st = window.__ccStats = { on: false, frames: 0, start: 0, device: '', deviceAt: 0 };
              function hook() {
                if (typeof ig === 'undefined' || !ig.system || !ig.system.run) { setTimeout(hook, 500); return; }
                if (ig.system.__ccCounted) return;
                ig.system.__ccCounted = true;
                var run = ig.system.run;
                ig.system.run = function () { st.frames++; return run.apply(this, arguments); };
              }
              hook();
              function size() {
                try { var c = ig.system.canvas; return c.width + 'x' + c.height; } catch (e) { return '?'; }
              }
              setInterval(function () {
                if (!st.on) return;
                var now = performance.now();
                var fps = st.start ? Math.round(st.frames * 1000 / (now - st.start)) : 0;
                st.frames = 0; st.start = now;
                if (now - st.deviceAt > 2000) {
                  st.deviceAt = now;
                  try { st.device = window.CCStats ? window.CCStats.read() : ''; } catch (e) { st.device = ''; }
                }
                box.textContent = fps + ' fps · ' + size() + (st.device ? ' · ' + st.device : '');
              }, 500);
              st.show = function (on) {
                st.on = !!on; st.frames = 0; st.start = 0; st.deviceAt = 0;
                box.style.display = on ? 'block' : 'none';
              };
              st.show(__ON__);
            })();
        """.trimIndent()
    }
}
