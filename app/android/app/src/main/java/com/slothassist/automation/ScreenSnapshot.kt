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
)

/**
 * A numbered, reading-order view of the foreground app: interactive elements plus loose text.
 * Text that sits inside a clickable card is folded into the card's label, so
 * "India Gate Basmati Rice 1 kg ₹189" is one tappable item rather than four fragments.
 */
class ScreenSnapshot private constructor(
  val packageName: String,
  val elements: List<UiElement>,
  val scrollable: Boolean,
  val fingerprint: Int,
) {

  fun describe(): String = buildString {
    append("Current app: ").append(packageName).append('\n')
    append("Screen").append(if (scrollable) " (scrollable, more may be below)" else "").append(":\n")
    if (elements.isEmpty()) append("(nothing readable on screen)\n")
    for (e in elements) {
      append('[').append(e.id).append("] ").append(e.kind).append(" \"").append(e.label).append('"')
      append(" @").append(e.bounds.centerX()).append(',').append(e.bounds.centerY())
      if (e.node.isChecked) append(" checked")
      if (e.node.isSelected) append(" selected")
      if (e.node.isFocused && e.node.isEditable) append(" focused")
      append('\n')
    }
  }

  companion object {
    private const val MAX_ELEMENTS = 80
    private const val MAX_LABEL = 90

    fun capture(root: AccessibilityNodeInfo): ScreenSnapshot {
      val found = ArrayList<Triple<AccessibilityNodeInfo, String, String>>()
      var scrollable = false

      fun walk(n: AccessibilityNodeInfo, insideInteractive: Boolean, depth: Int) {
        if (!n.isVisibleToUser || depth > 40) return
        if (n.isScrollable) scrollable = true
        val interactive = n.isEditable || n.isCheckable || (n.isClickable && n.isEnabled)
        val own = ownText(n)
        when {
          interactive -> {
            val label = if (n.isEditable) editableLabel(n) else own.ifBlank { mergedText(n) }.ifBlank { idHint(n) }
            found.add(Triple(n, kindOf(n), label))
          }
          !insideInteractive && own.isNotBlank() -> found.add(Triple(n, "text", own))
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, insideInteractive || interactive, depth + 1) }
      }
      walk(root, false, 0)

      val rowHeight = 32
      val ordered = found
        .map { it to ScreenReader.bounds(it.first) }
        .filter { (_, b) -> b.width() > 0 && b.height() > 0 }
        .sortedWith(compareBy({ it.second.top / rowHeight }, { it.second.left }))
        .take(MAX_ELEMENTS)

      val elements = ordered.mapIndexed { i, (t, b) ->
        UiElement(i, t.first, t.second, t.third.replace('\n', ' ').replace('"', '\'').take(MAX_LABEL), b)
      }
      return ScreenSnapshot(
        root.packageName?.toString() ?: "unknown",
        elements,
        scrollable,
        ScreenReader.fingerprint(root),
      )
    }

    private fun ownText(n: AccessibilityNodeInfo): String =
      (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: "").trim()

    private fun editableLabel(n: AccessibilityNodeInfo): String {
      val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString() else null
      val text = n.text?.toString().orEmpty()
      return when {
        text.isNotBlank() && hint != null && text != hint -> "$text (hint: $hint)"
        text.isNotBlank() -> text
        hint != null -> "hint: $hint"
        else -> idHint(n)
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
        n.isEditable -> "input"
        n.isCheckable -> if (cls.contains("Switch")) "switch" else "checkbox"
        cls.contains("Image") -> "icon"
        cls.contains("Button") -> "button"
        n.childCount > 1 -> "item"
        else -> "button"
      }
    }
  }
}
