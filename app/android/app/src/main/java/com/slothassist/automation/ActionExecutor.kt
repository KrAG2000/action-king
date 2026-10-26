package com.slothassist.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * A step didn't work. Non-fatal failures are reported back to the planner so it can try
 * something else; fatal ones (stopped, app switched, payment button) end the task.
 */
class TaskFailed(message: String, val fatal: Boolean = false) : Exception(message)

/**
 * Performs UI actions on the foreground app and checks that they worked.
 * Every method runs on the task thread and blocks; [cancelled] is checked between steps.
 */
class ActionExecutor(
  private val svc: AccessibilityService,
  private val cancelled: () -> Boolean,
  private val status: (String) -> Unit,
) {

  private var targetPackage: String? = null

  // ---------- observing ----------

  fun observe(): ScreenSnapshot {
    val root = rootOrFail()
    return ScreenSnapshot.capture(root).also { Log.d(TAG, it.describe()) }
  }

  /**
   * Re-reads the screen until [ready] holds (lists that render progressively, steppers that appear
   * after a tap), up to [timeoutMs]. Returns the last read either way.
   */
  fun observeUntil(timeoutMs: Long, ready: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
    var screen = observe()
    val end = System.currentTimeMillis() + timeoutMs
    while (!ready(screen) && System.currentTimeMillis() < end) {
      sleep(250)
      screen = observe()
    }
    return screen
  }

  /** FR-047: refuse to act if the user switched apps while the planner was thinking. */
  fun guard(expectedPackage: String) {
    val now = rootOrFail().packageName?.toString()
    if (now != expectedPackage) throw TaskFailed("You switched apps, so I stopped.", fatal = true)
  }

  // ---------- search ----------

  fun search(query: String): String {
    if (query.isBlank()) throw TaskFailed("What should I search for?")
    targetPackage = rootOrFail().packageName?.toString()
    try {
      status("Looking for a search bar…")
      var field = findSearchField()
      if (field == null) {
        // Many apps show a fake search box or a magnifier icon that opens the real input.
        val trigger = findSearchTrigger() ?: throw TaskFailed("I couldn't find a search bar on this screen.")
        status("Opening search…")
        click(trigger)
        field = waitFor(2500) { findSearchField() }
          ?: throw TaskFailed("I tapped search, but no text box appeared.")
      }

      status("Typing \"$query\"…")
      focus(field)
      field = findSearchField() ?: field
      setText(field, query)

      status("Submitting…")
      val before = ScreenReader.fingerprint(currentRoot())
      submit(field)

      val changed = waitFor(5000) {
        val stillEditing = findSearchField()?.let { it.isFocused && it.text?.toString() == query } ?: false
        if (ScreenReader.fingerprint(currentRoot()) != before && !stillEditing) true else null
      }
      settle()
      if (changed == true) return "searched for \"$query\", results are showing"
      // Re-running the search already on screen changes nothing; that's fine once we've left the text box.
      val stillEditing = findSearchField()?.isFocused == true && hasKeyboardOpen()
      if (!stillEditing) return "searched for \"$query\", results are showing (same as before)"
      throw TaskFailed("I submitted \"$query\", but couldn't confirm that results appeared.")
    } finally {
      targetPackage = null
    }
  }

  private fun findSearchField(): AccessibilityNodeInfo? {
    val root = currentRoot() ?: return null
    return ScreenReader.flatten(root)
      .filter { it.isEditable && it.isVisibleToUser && it.isEnabled && !it.isPassword }
      .maxByOrNull { scoreField(it) }
  }

  private fun scoreField(n: AccessibilityNodeInfo): Int {
    val label = ScreenReader.label(n)
    var score = 0
    if (SEARCH_WORDS.any { label.contains(it) }) score += 50
    if (n.isFocused) score += 20
    // Search bars live near the top of the screen.
    score += (20 - ScreenReader.bounds(n).top / 100).coerceAtLeast(0)
    return score
  }

  private fun findSearchTrigger(): AccessibilityNodeInfo? {
    val root = currentRoot() ?: return null
    return ScreenReader.flatten(root)
      .filter { it.isVisibleToUser && SEARCH_WORDS.any { w -> ScreenReader.label(it).contains(w) } }
      .mapNotNull { n -> ScreenReader.clickableAncestor(n)?.let { n to it } }
      // Prefer a visible "Search…" text box over a bare icon, and things higher on screen.
      .maxByOrNull { (n, _) ->
        (if (!n.text.isNullOrBlank()) 30 else 0) + (20 - ScreenReader.bounds(n).top / 100).coerceAtLeast(0)
      }
      ?.second
  }

  private fun submit(field: AccessibilityNodeInfo) {
    if (pressEnter(field)) return
    // Fallback: a nearby "Search"/"Go" button.
    val root = currentRoot() ?: throw TaskFailed("The app closed while I was searching.")
    val button = ScreenReader.flatten(root).firstOrNull {
      it != field && it.isVisibleToUser && it.isClickable && !it.isEditable &&
        SUBMIT_WORDS.any { w -> ScreenReader.label(it).contains(w) }
    } ?: throw TaskFailed("I typed the query, but this app has no way for me to press Enter.")
    click(button)
  }

  // ---------- element actions ----------

  fun tap(el: UiElement, before: ScreenSnapshot): String {
    if (!el.node.refresh() || !el.node.isVisibleToUser) throw TaskFailed("\"${el.label}\" is no longer on screen.")
    click(el.node)
    if (settle(before.fingerprint)) return "tapped \"${el.label}\", the screen changed"
    // Web content (Chrome, in-app browsers) often ignores accessibility clicks; a real touch works.
    tapAt(el.node)
    sleep(300)
    return if (settle(before.fingerprint)) "tapped \"${el.label}\", the screen changed"
    else "tapped \"${el.label}\", but nothing changed. Don't tap it again; try something else"
  }

  /** Whether typing into [el] really means "search for this" (search box or browser address bar). */
  fun isSearchBox(el: UiElement): Boolean =
    el.node.isEditable && SEARCH_WORDS.any { ScreenReader.label(el.node).contains(it) }

  fun type(el: UiElement, text: String, before: ScreenSnapshot): String {
    if (!el.node.refresh()) throw TaskFailed("That text box is no longer on screen.")
    if (!el.node.isEditable) throw TaskFailed("\"${el.label}\" is not a text box.")
    focus(el.node)
    setText(el.node, text)
    settle(before.fingerprint)
    return "typed \"$text\" into \"${el.label}\""
  }

  fun openUrl(url: String): String {
    checkCancelled()
    val uri = Uri.parse(if (url.contains("://")) url else "https://$url")
    if (uri.scheme !in listOf("http", "https")) throw TaskFailed("I only open web links (http/https).")
    svc.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    sleep(1500)
    settle()
    return "opened $uri"
  }

  fun scroll(forward: Boolean): String {
    val root = rootOrFail()
    val target = ScreenReader.flatten(root)
      .filter { it.isScrollable && it.isVisibleToUser }
      .maxByOrNull { ScreenReader.bounds(it).let { b -> b.width() * b.height() } }
    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    val before = ScreenReader.fingerprint(root)
    if (target?.performAction(action) != true) swipe(forward)
    return if (settle(before)) "scrolled ${if (forward) "down" else "up"}"
    else throw TaskFailed("I tried to scroll ${if (forward) "down" else "up"}, but nothing moved. Probably the end of the list.")
  }

  fun back(): String {
    checkCancelled()
    val before = ScreenReader.fingerprint(ScreenReader.targetRoot(svc))
    if (!svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) throw TaskFailed("Back didn't work.")
    settle(before)
    return "went back"
  }

  // ---------- helpers ----------

  private fun rootOrFail() = ScreenReader.targetRoot(svc)
    ?: throw TaskFailed("I can't read the current screen. Some apps block assistants.", fatal = true)

  private fun currentRoot(): AccessibilityNodeInfo? {
    checkCancelled()
    val root = ScreenReader.targetRoot(svc) ?: return null
    if (targetPackage != null && root.packageName?.toString() != targetPackage) {
      throw TaskFailed("You switched apps, so I stopped.", fatal = true)
    }
    return root
  }

  private fun click(n: AccessibilityNodeInfo) {
    checkCancelled()
    val target = ScreenReader.clickableAncestor(n) ?: n
    val label = ScreenReader.label(n) + " " + ScreenReader.label(target)
    if (SENSITIVE.containsMatchIn(label)) {
      throw TaskFailed("That button looks like a payment or order step. Please tap it yourself.", fatal = true)
    }
    if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tapAt(target)
    sleep(300)
  }

  private fun focus(n: AccessibilityNodeInfo) {
    if (n.isFocused) return
    if (!n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    sleep(300)
  }

  private fun setText(field: AccessibilityNodeInfo, text: String) {
    val args = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
      throw TaskFailed("This text box doesn't accept typed text.")
    }
    sleep(300)
    field.refresh()
    if (field.text?.toString()?.contains(text, ignoreCase = true) != true) {
      throw TaskFailed("I typed \"$text\", but the text box doesn't show it.")
    }
  }

  private fun hasKeyboardOpen(): Boolean =
    svc.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }

  private fun pressEnter(field: AccessibilityNodeInfo): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      field.actionList.any { it.id == AccessibilityAction.ACTION_IME_ENTER.id } &&
      field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)

  private fun tapAt(n: AccessibilityNodeInfo) {
    val b = ScreenReader.bounds(n)
    val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
    svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
  }

  private fun swipe(forward: Boolean) {
    val dm = svc.resources.displayMetrics
    val x = dm.widthPixels / 2f
    val (from, to) = if (forward) dm.heightPixels * 0.75f to dm.heightPixels * 0.3f else dm.heightPixels * 0.3f to dm.heightPixels * 0.75f
    val path = Path().apply { moveTo(x, from); lineTo(x, to) }
    svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
  }

  /**
   * Waits until the screen stops changing (animations, network loads), up to a few seconds.
   * Returns whether it ended up different from [before].
   */
  private fun settle(before: Int? = null): Boolean {
    var last = ScreenReader.fingerprint(ScreenReader.targetRoot(svc))
    var stableFor = 0
    var changed = before != null && last != before
    val end = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() < end && stableFor < 2) {
      sleep(250)
      val now = ScreenReader.fingerprint(ScreenReader.targetRoot(svc))
      if (now == last) stableFor++ else { stableFor = 0; last = now }
      if (before != null && now != before) changed = true
    }
    return changed
  }

  private fun <T> waitFor(timeoutMs: Long, probe: () -> T?): T? {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      probe()?.let { return it }
      sleep(150)
    }
    return null
  }

  private fun sleep(ms: Long) {
    checkCancelled()
    Thread.sleep(ms)
    checkCancelled()
  }

  fun checkCancelled() {
    if (cancelled()) throw TaskFailed("Stopped.", fatal = true)
  }

  companion object {
    private const val TAG = "SlothExecutor"
    private val SEARCH_WORDS = listOf("search", "url_bar", "address", "query", "find", "खोज")
    private val SUBMIT_WORDS = listOf("search", "go", "submit", "enter")
    private val SENSITIVE = Regex("\\b(pay|place order|buy now|checkout|check out|confirm order|purchase|proceed to pay)\\b")
  }
}
