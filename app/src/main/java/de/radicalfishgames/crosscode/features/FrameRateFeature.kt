package de.radicalfishgames.crosscode.features

import android.util.Log
import de.radicalfishgames.crosscode.FrameRate
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameFiles
import de.radicalfishgames.crosscode.GameWrapper

// The frame-rate limit (Settings, and the in-game pause menu). CrossCode draws from
// window.requestAnimationFrame, looked up by name on every frame, so wrapping that function gates
// the frames; the engine keeps its own clock, so the game runs at normal speed and only draws less
// often. Nothing in the game's files is touched.
//
// The gate keeps a running deadline: the next frame is due one interval after the last one was due,
// not after it was drawn. A limit the screen divides evenly is even (30 on 60 Hz is every second
// refresh), and one it does not averages out (45 on 60 Hz draws on three refreshes of every four).
class FrameRateFeature(gameWrapper: GameWrapper, hostActivity: GameActivity) : Feature(gameWrapper, hostActivity) {

    var limit: Int = FrameRate.normalize(GameFiles.fpsLimit(hostActivity), hostActivity.frameRateChoices)
        private set

    override fun onPostGamePageLoad() {
        runJS(GATE_JS.replace("__LIMIT__", limit.toString()))
        Log.i("CrossCode", "[FrameRate] limit ${FrameRate.label(limit)}")
    }

    // Changes the limit now and remembers it.
    fun set(fps: Int) {
        limit = fps
        GameFiles.setFpsLimit(hostActivity, fps)
        runJS("if(window.__ccFps){window.__ccFps.limit=$fps;window.__ccFps.next=0;}")
        Log.i("CrossCode", "[FrameRate] limit ${FrameRate.label(fps)}")
    }

    companion object {
        private val GATE_JS = """
            (function () {
              if (window.__ccFps) { window.__ccFps.limit = __LIMIT__; window.__ccFps.next = 0; return; }
              var raf = window.requestAnimationFrame, caf = window.cancelAnimationFrame;
              if (typeof raf !== 'function') return;
              var st = window.__ccFps = { limit: __LIMIT__, next: 0, granted: -1 };
              var jobs = {}, seq = 1000000000;
              function due(t) {
                if (t === st.granted) return true;
                var ms = 1000 / st.limit;
                if (t < st.next - ms / 10) return false;
                st.next += ms;
                if (st.next < t) st.next = t + ms;
                st.granted = t;
                return true;
              }
              window.requestAnimationFrame = function (cb, el) {
                if (!st.limit) return raf.call(window, cb, el);
                var id = seq++, job = jobs[id] = { dead: false, real: 0 };
                function tick(t) {
                  if (job.dead) { delete jobs[id]; return; }
                  if (!st.limit || due(t)) { delete jobs[id]; cb(t); }
                  else job.real = raf.call(window, tick, el);
                }
                job.real = raf.call(window, tick, el);
                return id;
              };
              window.cancelAnimationFrame = function (id) {
                var job = jobs[id];
                if (job) { job.dead = true; delete jobs[id]; if (caf) caf.call(window, job.real); }
                else if (caf) caf.call(window, id);
              };
              console.log('[cc-fps] frame gate installed, limit ' + st.limit);
            })();
        """.trimIndent()
    }
}
