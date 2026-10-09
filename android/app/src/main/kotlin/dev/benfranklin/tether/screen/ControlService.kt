package dev.benfranklin.tether.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.benfranklin.tether.core.features.GestureMapper
import dev.benfranklin.tether.core.proto.InputKinds
import dev.benfranklin.tether.core.proto.ScreenInput
import dev.benfranklin.tether.log.Log
import java.lang.ref.WeakReference

/** Text editing for keyboard input from the Mac. Pure; unit-tested. */
object TextEdit {
    /** The field's new text after typing [typed] at the end (a hint showing counts as empty). */
    fun append(current: CharSequence?, showingHint: Boolean, typed: String): String =
        (if (showingHint) "" else current?.toString().orEmpty()) + typed

    /** The field's new text after one backspace (removes a whole code point, e.g. an emoji half-pair). */
    fun backspace(current: CharSequence?, showingHint: Boolean): String {
        val s = if (showingHint) "" else current?.toString().orEmpty()
        if (s.isEmpty()) return s
        val cut = s.offsetByCodePoints(s.length, -1)
        return s.substring(0, cut)
    }
}

/**
 * Accessibility service used only for screen control from the Mac while the user is sharing
 * the screen: taps, long presses, swipes and scrolls via dispatchGesture, typed text via
 * ACTION_SET_TEXT on the focused field, and Back/Home/Recents via global actions. It handles
 * no accessibility events and never reads or keeps screen content.
 */
class ControlService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = WeakReference(this)
        Log.i("control.connected")
        ScreenCaptureService.active?.refreshNotification()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i("control.disconnected")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Perform [input]; the frame it refers to was captured at rotation [frameRotation]. */
    fun perform(input: ScreenInput, frameRotation: Int): Boolean {
        val d = currentDisplay(this)
        val rot = input.rot ?: frameRotation
        fun at(x: Double, y: Double) = GestureMapper.toDisplay(x, y, rot, d.rotation, d.width, d.height)
        return when (input.k) {
            InputKinds.TAP -> stroke(at(input.x, input.y), null, TAP_MS)
            InputKinds.LONG_PRESS -> stroke(at(input.x, input.y), null, (input.dur ?: LONG_MS).coerceIn(LONG_MS, MAX_MS))
            InputKinds.SWIPE, InputKinds.SCROLL -> {
                val end = at(input.x2 ?: input.x, input.y2 ?: input.y)
                stroke(at(input.x, input.y), end, (input.dur ?: SWIPE_MS).coerceIn(MIN_SWIPE_MS, MAX_MS))
            }
            InputKinds.TEXT -> editFocused { node -> TextEdit.append(node.text, hint(node), input.text.orEmpty()) }
            InputKinds.BACKSPACE -> editFocused { node -> TextEdit.backspace(node.text, hint(node)) }
            InputKinds.ENTER -> enter()
            InputKinds.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            InputKinds.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            InputKinds.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }

    private fun stroke(from: GestureMapper.Point, to: GestureMapper.Point?, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(from.x, from.y)
            // A zero-length path is a tap; a tiny segment keeps some OEM gesture stacks happy.
            if (to != null) lineTo(to.x, to.y) else lineTo(from.x + 1f, from.y)
        }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGesture(g, null, null)
    }

    private fun hint(n: AccessibilityNodeInfo): Boolean = n.isShowingHintText

    private fun focused(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun editFocused(change: (AccessibilityNodeInfo) -> String): Boolean {
        val node = focused() ?: return false
        val text = change(node)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

    private fun enter(): Boolean {
        val node = focused() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return node.isMultiLine && editFocused { TextEdit.append(it.text, hint(it), "\n") }
    }

    companion object {
        private const val TAP_MS = 40L
        private const val LONG_MS = 600L
        private const val SWIPE_MS = 250L
        private const val MIN_SWIPE_MS = 40L
        private const val MAX_MS = 5_000L
        @Volatile private var instance: WeakReference<ControlService>? = null

        val current: ControlService? get() = instance?.get()

        val available: Boolean get() = current != null

        /** Enabled in Settings (it may still be binding). */
        fun enabledInSettings(c: Context): Boolean {
            val me = ComponentName(c, ControlService::class.java).flattenToString()
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
