package com.slothassist.automation

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** One thing on screen the agent can refer to by number. */
data class UiElement(
  val id: Int,
  val node: AccessibilityNodeInfo,
  val kind: String,
  val label: String,
  val bounds: Rect,
  /** Placeholder text of an input, kept apart from [label] so the model never copies it as a target. */
  val hint: String? = null,
  /** id of the card (clickable item) this element sits inside, e.g. the ADD button of a product. */
  val card: Int? = null,
)

/**
 * A numbered, reading-order view of the foreground app — read from the accessibility tree, not a screenshot.
 * Text inside a clickable card is folded into the card's label, so "India Gate Basmati Rice 1 kg ₹189"
 * is one item, and the card's own buttons point back at it ("ADD in [4]").
 * When a sheet, dialog or pop-up is open, only that top layer is listed: it's what the user sees.
 */
class ScreenSnapshot private constructor(
  val packageName: String,
  val elements: List<UiElement>,
  val scrollable: Boolean,
  val fingerprint: Int,
  /** Title of the open sheet/dialog when only the top layer is listed. */
  val topLayer: String?,
) {

  fun describe(limit: Int = elements.size): String = buildString {
    append("Current app: ").append(packageName).append('\n')
    if (topLayer != null) append("A pop-up/sheet is open on top: \"").append(topLayer).append("\". Only it is listed; the page behind is hidden.\n")
    append("Screen").append(if (scrollable) " (scrollable, more may be below)" else "").append(":\n")
    if (elements.isEmpty()) append("(nothing readable on screen)\n")
    for (e in elements.take(limit)) {
      append('[').append(e.id).append("] ").append(e.kind).append(" \"").append(e.label).append('"')
      if (e.hint != null) append(" placeholder \"").append(e.hint).append('"')
      if (e.card != null) append(" in [").append(e.card).append(']')
      if (e.node.isChecked) append(" checked")
      if (e.node.isSelected) append(" selected")
      if (e.node.isFocused && e.node.isEditable) append(" focused")
      append('\n')
    }
  }

  /** Elements inside [card] (by bounds), e.g. the ADD / − / count / + of one product. */
  fun inside(card: UiElement): List<UiElement> =
    elements.filter { it.id != card.id && card.bounds.contains(it.bounds) }

  companion object {
    // Every element costs prompt tokens, and prefill dominates on-device latency.
    private const val MAX_ELEMENTS = 60
    private const val MAX_LABEL = 60
    private val OVERLAY_CLASS = Regex("Dialog|BottomSheet|Modal|Popup", RegexOption.IGNORE_CASE)

    fun capture(root: AccessibilityNodeInfo): ScreenSnapshot {
      val overlay = findOverlay(root)
      val found = ArrayList<Triple<AccessibilityNodeInfo, String, Pair<String, String?>>>()
      var scrollable = false

      fun walk(n: AccessibilityNodeInfo, insideInteractive: Boolean, depth: Int) {
        if (!n.isVisibleToUser || depth > 40) return
        if (n.isScrollable) scrollable = true
        val interactive = n.isEditable || n.isCheckable || (n.isClickable && n.isEnabled)
        val own = ownText(n)
        when {
          n.isEditable -> found.add(Triple(n, "input", editableLabel(n)))
          interactive -> found.add(Triple(n, kindOf(n), own.ifBlank { mergedText(n) }.ifBlank { idHint(n) } to null))
          !insideInteractive && own.isNotBlank() -> found.add(Triple(n, "text", own to null))
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, insideInteractive || interactive, depth + 1) }
      }
      walk(overlay?.first ?: root, false, 0)

      val rowHeight = 32
      val visible = found
        .map { it to ScreenReader.bounds(it.first) }
        .filter { (_, b) -> b.width() > 0 && b.height() > 0 }
      // Over budget: keep everything you can act on before plain text, then restore reading order.
      val kept = if (visible.size <= MAX_ELEMENTS) visible
      else visible.sortedBy { if (it.first.second == "text") 1 else 0 }.take(MAX_ELEMENTS)
      val ordered = kept.sortedWith(compareBy({ it.second.top / rowHeight }, { it.second.left }))

      val base = ordered.mapIndexed { i, (t, b) ->
        val (label, hint) = t.third
        UiElement(i, t.first, t.second, clean(label), b, hint?.let(::clean))
      }
      // Point each control at the smallest card that contains it.
      val cards = base.filter { it.kind == "item" }
      val elements = base.map { e ->
        if (e.kind == "item") e
        else e.copy(card = cards.filter { it.bounds.contains(e.bounds) && it.bounds != e.bounds }
          .minByOrNull { it.bounds.width() * it.bounds.height() }?.id)
      }
      return ScreenSnapshot(
        root.packageName?.toString() ?: "unknown",
        elements,
        scrollable,
        ScreenReader.fingerprint(root),
        overlay?.second,
      )
    }

    /** What changed between two reads, as short lines for the task log (no screenshot needed). */
    fun diff(before: ScreenSnapshot, after: ScreenSnapshot, limit: Int = 6): List<String> {
      val out = ArrayList<String>()
      if (after.packageName != before.packageName) out += "now in app ${after.packageName}"
      if (after.topLayer != before.topLayer) {
        out += if (after.topLayer != null) "pop-up opened: \"${after.topLayer}\"" else "pop-up closed"
      }
      fun keys(s: ScreenSnapshot) = s.elements.map { "${it.kind} \"${it.label}\"" }
      val b = keys(before).groupingBy { it }.eachCount()
      val a = keys(after).groupingBy { it }.eachCount()
      val added = a.filter { (k, n) -> n > (b[k] ?: 0) }.keys.take(limit)
      val gone = b.filter { (k, n) -> n > (a[k] ?: 0) }.keys.take(limit)
      if (added.isNotEmpty()) out += "new: " + added.joinToString(", ")
      if (gone.isNotEmpty()) out += "gone: " + gone.joinToString(", ")
      return out
    }

    /**
     * A sheet or dialog drawn inside the app's own window (separate dialog windows are already handled by
     * [ScreenReader.targetRoot]). The last match in tree order is the one painted on top.
     */
    private fun findOverlay(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
      val rootArea = ScreenReader.bounds(root).let { it.width().toLong() * it.height() }
      if (rootArea <= 0) return null
      var hit: Pair<AccessibilityNodeInfo, String>? = null
      for (n in ScreenReader.flatten(root)) {
        if (n == root || !n.isVisibleToUser) continue
        val pane = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) n.paneTitle?.toString() else null
        val cls = n.className?.toString().orEmpty()
        if (pane.isNullOrBlank() && !OVERLAY_CLASS.containsMatchIn(cls)) continue
        val b = ScreenReader.bounds(n)
        val area = b.width().toLong() * b.height()
        if (area * 100 < rootArea * 15 || area * 100 > rootArea * 98) continue // too small, or just the page
        hit = n to (pane?.takeIf { it.isNotBlank() } ?: ownText(n).ifBlank { mergedText(n) }.take(MAX_LABEL).ifBlank { "pop-up" })
      }
      return hit
    }

    private fun clean(s: String) = s.replace('\n', ' ').replace('"', '\'').trim().take(MAX_LABEL)

    private fun ownText(n: AccessibilityNodeInfo): String =
      (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: "").trim()

    /** (label, placeholder): the typed text is the label; the hint is only shown separately when it says something. */
    private fun editableLabel(n: AccessibilityNodeInfo): Pair<String, String?> {
      val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() } else null
      val text = n.text?.toString()?.trim().orEmpty().takeIf { it != hint }.orEmpty()
      return when {
        text.isNotEmpty() -> text to hint
        hint != null -> hint to null
        else -> idHint(n) to null
      }
    }

    /** Text of all descendants, for cards and rows whose label lives in their children. */
    private fun mergedText(n: AccessibilityNodeInfo): String {
      val parts = ArrayList<String>()
      fun collect(c: AccessibilityNodeInfo, depth: Int) {
        if (parts.sumOf { it.length } > MAX_LABEL || depth > 8) return
        ownText(c).takeIf { it.isNotBlank() }?.let(parts::add)
        for (i in 0 until c.childCount) c.getChild(i)?.let { collect(it, depth + 1) }
      }
      for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, 0) }
      return parts.distinct().joinToString(" ")
    }

    private fun idHint(n: AccessibilityNodeInfo): String =
      n.viewIdResourceName?.substringAfter(":id/")?.let { "id:$it" } ?: ""

    private fun kindOf(n: AccessibilityNodeInfo): String {
      val cls = n.className?.toString().orEmpty()
      return when {
        n.isCheckable -> if (cls.contains("Switch")) "switch" else "checkbox"
        cls.contains("Image") -> "icon"
        cls.contains("Button") -> "button"
        n.childCount > 1 -> "item"
        else -> "button"
      }
    }
  }
}
