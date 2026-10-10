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
import dev.benfranklin.tether.runtime
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
 * ACTION_SET_TEXT on the focused field (screen control and the 0.5 keyboard window), and
 * Back/Home/Recents via global actions. Only when "Current browser tab" is turned on (Continue
 * on Mac, off by default) does it read one thing: the address bar of Chrome, Brave, Edge or
 * Samsung Internet. Nothing else on screen is read or kept.
 */
class ControlService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = WeakReference(this)
        val s = applicationContext.runtime.settings.current
        configureTabs(s.continueCards && s.continueTabs)
        Log.i("control.connected")
        ScreenCaptureService.active?.refreshNotification()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i("control.disconnected")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!tabsOn) return
        val pkg = e.packageName?.toString() ?: return
        val browser = BrowserTabs.browsers[pkg] ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTabScan < TAB_SCAN_MS) return
        lastTabScan = now
        val root = rootInActiveWindow ?: return
        val url = BrowserTabs.urlBarIds(pkg).firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { !it.isFocused }?.text?.toString()
        } ?: return
        val clean = BrowserTabs.clean(url) ?: return
        if (clean == lastTab) return
        lastTab = clean
        applicationContext.runtime.v5.browserTab(clean, null, browser)
    }

    private var tabsOn = false
    private var lastTab: String? = null
    private var lastTabScan = 0L

    /** Follow the browser tab (Continue on Mac). Off = no content events are read at all. */
    fun configureTabs(on: Boolean) {
        if (on == tabsOn) return
        tabsOn = on
        val info = serviceInfo ?: return
        info.eventTypes = if (on) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        serviceInfo = info
        Log.i("control.tabs", "on" to on)
    }

    /** Typing from the Mac's keyboard window (TYPE_TEXT). */
    fun type(t: dev.benfranklin.tether.core.proto.TypeText): dev.benfranklin.tether.core.proto.TypeResult {
        val node = focused() ?: return dev.benfranklin.tether.core.proto.TypeResult(t.seq, false, "no_field")
        val field = listOfNotNull(node.packageName?.toString()?.substringAfterLast('.'), node.hintText?.toString())
            .joinToString(" · ").take(FIELD_MAX)
        val ok = when (t.key) {
            "enter" -> enter()
            null, "backspace", "tab", "clear" -> {
                val current = if (hint(node)) "" else node.text?.toString().orEmpty()
                val cursor = node.textSelectionEnd.takeIf { it >= 0 && !hint(node) }
                val r = dev.benfranklin.tether.core.features.TypeEditor.apply(current, cursor, t.text, t.key)
                setText(node, r.text, r.cursor)
            }
            else -> false
        }
        return dev.benfranklin.tether.core.proto.TypeResult(t.seq, ok, if (ok) null else "failed", field)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        var ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            // Some fields refuse SET_TEXT on a stale node: refresh it once and retry.
            ok = node.refresh() && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i("type.set_text_retry", "ok" to ok)
        }
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }

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

    /**
     * The field to type into. `findFocus(FOCUS_INPUT)` on the active window misses Compose and
     * WebView fields on some OEM builds and fields in other windows (0.5.1): fall back to every
     * window's input focus, then to the focused (or only) editable node in the active window.
     */
    private fun focused(): AccessibilityNodeInfo? {
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        for (w in runCatching { windows }.getOrNull().orEmpty()) {
            w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        }
        val root = rootInActiveWindow ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectEditable(root, nodes, 0)
        val candidates = nodes.mapIndexed { i, n ->
            dev.benfranklin.tether.core.features.FocusPick.Candidate(i, n.isEditable, n.isFocused, n.isVisibleToUser)
        }
        val pick = dev.benfranklin.tether.core.features.FocusPick.pick(candidates) ?: return null
        return nodes[pick]
    }

    private fun collectEditable(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > MAX_DEPTH || out.size > MAX_EDITABLE) return
        if (n.isEditable) out += n
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectEditable(it, out, depth + 1) }
    }

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
        private const val TAB_SCAN_MS = 1_500L
        private const val FIELD_MAX = 80
        private const val MAX_DEPTH = 40
        private const val MAX_EDITABLE = 50
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

/** Address bars of the supported browsers (best-effort: view ids can change with browser updates). */
object BrowserTabs {
    val browsers: Map<String, String> = mapOf(
        "com.android.chrome" to "Chrome",
        "com.chrome.beta" to "Chrome Beta",
        "com.brave.browser" to "Brave",
        "com.microsoft.emmx" to "Edge",
        "com.sec.android.app.sbrowser" to "Samsung Internet",
    )

    fun urlBarIds(pkg: String): List<String> = when (pkg) {
        "com.sec.android.app.sbrowser" -> listOf("$pkg:id/location_bar_edit_text", "$pkg:id/custom_tab_toolbar_url_bar_text")
        else -> listOf("$pkg:id/url_bar", "$pkg:id/search_box_text")
    }

    /** "careers.armada.ai/apply" -> "https://careers.armada.ai/apply"; search terms and blanks are dropped. Pure; unit-tested. */
    fun clean(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.contains(' ') || !t.contains('.')) return null
        if (t.startsWith("chrome://") || t.startsWith("about:") || t.startsWith("edge://") || t.startsWith("internet://")) return null
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }
}
