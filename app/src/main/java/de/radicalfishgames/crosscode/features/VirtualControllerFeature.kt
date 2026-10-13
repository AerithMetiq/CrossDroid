package de.radicalfishgames.crosscode.features

import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameFiles
import de.radicalfishgames.crosscode.GameWrapper
import de.radicalfishgames.crosscode.GamepadJsonBridge
import de.radicalfishgames.crosscode.gamelisteners.GameEventManager
import de.radicalfishgames.crosscode.layouts.CombatLayout
import de.radicalfishgames.crosscode.layouts.DirectLayout
import de.radicalfishgames.crosscode.layouts.MenuLayout
import de.radicalfishgames.crosscode.layouts.VirtualControllerLayout
import de.radicalfishgames.crosscode.R


class VirtualControllerFeature(gameWrapper: GameWrapper,
                               hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    private val gamepadState: GamepadJsonBridge = GamepadJsonBridge {updateGamepadJS()}

    private val forceVirtualController = hostActivity.preferences.getBoolean("force_virtual", false)
    private val combatAlwaysSprint = hostActivity.preferences.getBoolean("always_sprint", true)
    private val dashOnlyOnTap = hostActivity.preferences.getBoolean("disable_swipe_dash", false)

    private val availableLayouts = setOf(CombatLayout, MenuLayout, DirectLayout)
    lateinit var currentLayout: VirtualControllerLayout
        private set

    // "Touch controls" off (Settings, or the pause menu) means no overlay at all, whatever else is set.
    internal val enabled = GameFiles.touchControls(hostActivity) &&
        (!hostActivity.isNativeControllerActive() || forceVirtualController)

    // False while the pause menu's switch has the overlay hidden for this session.
    var shown = enabled
        private set

    val layoutViews = HashMap<VirtualControllerLayout, ViewGroup>()

    private var isControllerReady = false


    override fun onPostGamePageLoad() {
        if(!enabled){
            return
        }

        // Hook the getGamepads-function to only return our gamepad (the browser's own is kept, so the
        // pause menu's "Touch controls" switch can hand the game back to a physical controller)
        runJS(
            """
                var $JS_GAMEPADS_VAR = [];
                window.__ccRealGetGamepads = window.__ccRealGetGamepads || navigator.getGamepads.bind(navigator);
                window.__ccTouchGetGamepads = function(){
                    return $JS_GAMEPADS_VAR;
                };
                navigator.getGamepads = window.__ccTouchGetGamepads;
            """
                .trimIndent()
                .replace("\n", "")
        )

        CombatLayout.alwaysSprint = combatAlwaysSprint
        CombatLayout.dashOnlyOnTap = dashOnlyOnTap

        initializeLayouts()

        currentLayout = DirectLayout
        DirectLayout.layoutToEnable = MenuLayout
        gameWrapper.blockWebViewClicks = false
        layoutViews[DirectLayout]!!.visibility = View.VISIBLE

        isControllerReady = true
    }

    private fun initializeLayouts(){

        for(controller in availableLayouts){

            val parentView = hostActivity.layoutInflater.inflate(controller.layoutResId, hostActivity.findViewById<ViewGroup>(R.id.control_container)) as ViewGroup
            parentView.visibility = View.VISIBLE

            val view = parentView.getChildAt(parentView.childCount - 1) as ViewGroup

            // Hide all controllers by default and only show them when they are needed
            view.visibility = View.GONE
            layoutViews[controller] = view

            controller.bindControls(gamepadState, view, this, gameWrapper.getFeature(GameEventManager::class))
        }
    }

    // The pause menu's "Touch controls" switch, for this session: hides the overlay and lets a
    // physical controller through, or brings the overlay back. Returns false when the overlay was
    // not set up at start (it was off, or a controller was connected): then only the stored setting
    // can change, and it applies the next time the game starts.
    fun show(on: Boolean): Boolean {
        if (!enabled || !isControllerReady) return false
        shown = on
        hostActivity.findViewById<View>(R.id.control_container).visibility = if (on) View.VISIBLE else View.GONE
        gameWrapper.blockWebViewClicks = on && !currentLayout.allowWebViewInteraction
        runJS(
            if (on) "navigator.getGamepads = window.__ccTouchGetGamepads;"
            else "navigator.getGamepads = window.__ccRealGetGamepads;"
        )
        return true
    }

    fun switchToLayout(newLayout: VirtualControllerLayout){
        if(!enabled){
            return
        }
        Log.d("CrossCode", "Switching to virtual controller ${newLayout.javaClass.simpleName}")

        layoutViews[currentLayout]!!.visibility = View.GONE
        currentLayout = newLayout
        layoutViews[currentLayout]!!.visibility = View.VISIBLE

        gameWrapper.blockWebViewClicks = shown && !currentLayout.allowWebViewInteraction
    }

    private fun updateGamepadJS(){
        if(!enabled){
            return
        }

        if(isControllerReady){
            val jsonGamepad = gamepadState.asJsonString

            runJS("$JS_GAMEPADS_VAR = [JSON.parse('$jsonGamepad')];")
        }
    }

    companion object {
        const val JS_GAMEPADS_VAR = "injectedGamepads"
    }
}