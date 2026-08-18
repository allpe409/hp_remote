package com.hpremote.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Receives input commands relayed from the web controller (via [RelayConnection],
 * fed by [ScreenCaptureService]) and injects them into the device using the
 * Accessibility API. Requires the user to enable this service manually in
 * system Settings; it cannot be turned on programmatically.
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteA11yService"
        private const val TAP_DURATION_MS = 60L
        var instance: RemoteAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed: this service only injects input, it doesn't observe events.
    }

    override fun onInterrupt() {}

    fun handleCommand(msg: JSONObject) {
        try {
            when (msg.optString("type")) {
                "tap" -> tap(msg.getDouble("x").toFloat(), msg.getDouble("y").toFloat())
                "swipe" -> swipe(
                    msg.getDouble("x1").toFloat(), msg.getDouble("y1").toFloat(),
                    msg.getDouble("x2").toFloat(), msg.getDouble("y2").toFloat(),
                    msg.optLong("duration", 200L)
                )
                "key" -> globalAction(msg.optString("action"))
                "text" -> setFocusedText(msg.optString("text"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to handle command: $msg", e)
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 60_000))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun globalAction(action: String) {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return
        }
        performGlobalAction(code)
    }

    private fun setFocusedText(text: String) {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            Log.w(TAG, "no focused input field to receive text")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
    }
}
