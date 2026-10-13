package de.radicalfishgames.crosscode

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

// The in-game menu, opened by Back: a scrim over the game and a panel pinned to the right edge with
// one full-width row per entry, in the style of Alabaster Droid's side menu. The game is paused
// while it is open (GameActivity does that), and a controller drives it.
//
// Nothing in here takes view focus, so the WebView keeps it: the selection is this view's own.
// GameActivity hands it the controller's keys (padKey) and the selected row wears a ring. Touch
// works as well: every row is its own tap target, and a tap on the scrim resumes the game.
//
// The view only shows what it is told (setTouchControls, setStats, setFrameRate) and reports what the player
// did through the callbacks; GameActivity owns the settings.
class PauseMenuView(context: Context) : FrameLayout(context) {

    var onTouchControls: ((Boolean) -> Unit)? = null
    // The frame-rate limit the player chose: FrameRate.OFF, or a rate.
    var onFrameRate: ((Int) -> Unit)? = null
    var onStats: ((Boolean) -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    // One stop for the controller: `activate` is its tap, `adjust` moves it left (-1) or right (+1).
    private class Stop(val view: View, val activate: (() -> Unit)?, val adjust: ((Int) -> Unit)?)

    private val stops = ArrayList<Stop>()
    private var selected = 0

    private val touchSwitch = Switch(context)
    private val limitSwitch = Switch(context)
    private val statsSwitch = Switch(context)
    private val rateValue = TextView(context)
    private lateinit var rateRow: View
    private lateinit var scroller: ScrollView

    private var touchControls = false
    private var stats = false
    private var limit = FrameRate.OFF
    private var choices = FrameRate.CHOICES
    // The rate the switch turns on: the last one chosen, 30 until there is one.
    private var lastRate = 30

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        visibility = GONE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(
            View(context).apply {
                setBackgroundColor(SCRIM)
                setOnClickListener { onResume?.invoke() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(buildPanel(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(dp(340), LayoutParams.MATCH_PARENT, Gravity.END))
        sync()
    }

    fun open() {
        visibility = VISIBLE
        scroller.scrollTo(0, 0)
        select(0)
    }

    fun close() { visibility = GONE }

    fun setTouchControls(on: Boolean) { touchControls = on; sync() }

    fun setStats(on: Boolean) { stats = on; sync() }

    // The limit in force (FrameRate.OFF for none) and the rates this screen is offered.
    fun setFrameRate(limit: Int, choices: List<Int>) {
        this.limit = limit
        this.choices = choices
        if (limit != FrameRate.OFF) lastRate = limit
        if (lastRate !in choices) lastRate = choices.first()
        sync()
    }

    // A controller key while the menu is open: up and down move the selection, A (or Enter) taps the
    // selected row, left and right change the rate when its row is selected. Returns whether the key
    // meant something here.
    fun padKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> stops.getOrNull(selected)?.adjust?.invoke(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> stops.getOrNull(selected)?.adjust?.invoke(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                stops.getOrNull(selected)?.activate?.invoke()
                select(selected)       // a switch can hide the row under it
            }
            else -> return false
        }
        return true
    }

    private fun move(step: Int) {
        var next = selected
        do { next += step } while (next in stops.indices && stops[next].view.visibility != VISIBLE)
        if (next in stops.indices) select(next)
    }

    private fun select(index: Int) {
        var target = index.coerceIn(0, maxOf(0, stops.size - 1))
        while (target > 0 && stops[target].view.visibility != VISIBLE) target--
        selected = target
        /* View.foreground is API 23+; below that the selection still works, without the ring. */
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            for ((i, stop) in stops.withIndex()) stop.view.foreground = if (i == selected) ring() else null
        }
        stops.getOrNull(selected)?.view?.let { v ->
            v.post { v.requestRectangleOnScreen(android.graphics.Rect(0, 0, v.width, v.height), false) }
        }
    }

    private fun ring() = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), TEXT)
    }

    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true          // its blank area must not fall through to the scrim
            setBackgroundColor(PANEL)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        panel.addView(TextView(context).apply {
            text = "Menu"
            textSize = 18f
            setTextColor(TEXT)
        })
        panel.addView(TextView(context).apply {
            text = "Game paused · D-pad: move · A: select · B: resume"
            textSize = 12f
            setTextColor(DIM)
            setPadding(0, dp(2), 0, dp(6))
        })

        addRow(panel, "Touch controls", touchSwitch) {
            touchControls = !touchControls
            sync()
            onTouchControls?.invoke(touchControls)
        }
        addRow(panel, "Show FPS / battery / temp", statsSwitch) {
            stats = !stats
            sync()
            onStats?.invoke(stats)
        }
        addRow(panel, "Limit the frame rate", limitSwitch) {
            limit = if (limit == FrameRate.OFF) lastRate else FrameRate.OFF
            sync()
            onFrameRate?.invoke(limit)
        }
        /* The rate lives under the switch, and only while it is on: left and right step it (a tap on
         * either half does the same). */
        rateValue.apply {
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
        }
        rateRow = addRow(panel, "Rate", rateValue, adjust = { step -> stepRate(step) }) { stepRate(1) }
        rateRow.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP) stepRate(if (e.x < v.width / 2f) -1 else 1)
            true
        }

        /* Pushes Resume and Exit to the bottom of the panel. */
        panel.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        addRow(panel, "Resume", null) { onResume?.invoke() }
        addRow(panel, "Exit", null, last = true) { onExit?.invoke() }
        return panel
    }

    private fun stepRate(step: Int) {
        if (limit == FrameRate.OFF) return
        val index = (choices.indexOf(limit).coerceAtLeast(0) + step).coerceIn(0, choices.size - 1)
        val next = choices[index]
        if (next == limit) return
        limit = next
        lastRate = next
        sync()
        onFrameRate?.invoke(limit)
    }

    // The panel's only row shape: the label on one line, the control (if any) at the right edge, and
    // the whole row is the hit target.
    private fun addRow(
        panel: LinearLayout, label: String, control: View?, last: Boolean = false,
        adjust: ((Int) -> Unit)? = null, onClick: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 14f
                    setTextColor(TEXT)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (control != null) {
                control.isClickable = false     // the row is the hit target
                control.isFocusable = false
                addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        panel.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (!last) {
            panel.addView(
                View(context).apply { setBackgroundColor(HAIRLINE) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            )
        }
        stops.add(Stop(row, onClick, adjust))
        return row
    }

    // The only writer of the widget state.
    private fun sync() {
        touchSwitch.isChecked = touchControls
        statsSwitch.isChecked = stats
        limitSwitch.isChecked = limit != FrameRate.OFF
        rateValue.text = "◀   ${if (limit == FrameRate.OFF) lastRate else limit} fps   ▶"
        if (::rateRow.isInitialized) rateRow.visibility = if (limit != FrameRate.OFF) VISIBLE else GONE
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        // The dark panel and warm off-white text of Alabaster Droid's in-game menu.
        const val TEXT = 0xFFE8DCC8.toInt()
        const val DIM = 0xB3E8DCC8.toInt()
        const val PANEL = 0xF0101820.toInt()
        const val SCRIM = 0x99000000.toInt()
        const val HAIRLINE = 0x33E8DCC8
    }
}
