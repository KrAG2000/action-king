package com.slothassist.automation

import android.app.Notification
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * Live context without screenshots: Android pushes accessibility events as things happen, and we
 * keep the ones that explain what an action caused — toasts and snackbars ("Out of stock"),
 * announcements ("Added to cart"), sheets and dialogs opening or closing, and short text changes
 * (a quantity going 1 → 2). The harness reads what arrived since each action.
 */
object EventJournal {

  private const val MAX_ENTRIES = 200
  private const val MAX_SHORT_TEXT = 24

  private val entries = ArrayDeque<Pair<Long, String>>()
  private var seq = 0L

  @Synchronized
  fun mark(): Long = seq

  /** Distinct events recorded after [mark], oldest first. */
  @Synchronized
  fun since(mark: Long, limit: Int = 6): List<String> =
    entries.filter { it.first > mark }.map { it.second }.distinct().takeLast(limit)

  fun record(event: AccessibilityEvent, ownPackage: String) {
    if (!TaskRunner.isRunning) return
    val pkg = event.packageName?.toString() ?: return
    if (pkg == ownPackage || pkg == "com.android.systemui") return
    describe(event)?.let { add(it) }
  }

  private fun describe(e: AccessibilityEvent): String? {
    val text = e.text.joinToString(" ").trim().take(80)
    return when (e.eventType) {
      AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
        // Toasts arrive here; real notifications carry a Notification parcel and are someone else's business.
        if (e.parcelableData !is Notification && text.isNotEmpty()) "message: \"$text\"" else null
      AccessibilityEvent.TYPE_ANNOUNCEMENT ->
        if (text.isNotEmpty()) "announced: \"$text\"" else null
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        val title = paneTitle(e) ?: text
        when {
          e.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0 ->
            "appeared: \"${title.ifEmpty { "a panel" }}\""
          e.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0 ->
            "closed: \"${title.ifEmpty { "a panel" }}\""
          title.isNotEmpty() -> "opened: \"$title\""
          else -> null
        }
      }
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        if (e.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT == 0) return null
        // Counters, badges and prices: short texts whose change is the evidence that a tap worked.
        val now = e.source?.text?.toString()?.trim().orEmpty()
        if (now.isNotEmpty() && now.length <= MAX_SHORT_TEXT) "now shows \"$now\"" else null
      }
      else -> null
    }
  }

  private fun paneTitle(e: AccessibilityEvent): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) e.source?.paneTitle?.toString()?.takeIf { it.isNotBlank() } else null

  @Synchronized
  private fun add(line: String) {
    entries.addLast(++seq to line)
    while (entries.size > MAX_ENTRIES) entries.removeFirst()
  }
}
