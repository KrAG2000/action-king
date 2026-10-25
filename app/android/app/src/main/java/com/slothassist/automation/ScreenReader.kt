package com.slothassist.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Reads the accessibility tree of the app the user is controlling (never our own overlay). */
object ScreenReader {

  private const val MAX_NODES = 2000

  /** Topmost application window that doesn't belong to SlothAssist. */
  fun targetRoot(svc: AccessibilityService): AccessibilityNodeInfo? {
    val own = svc.packageName
    for (w in svc.windows) { // ordered top to bottom
      if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
      val root = w.root ?: continue
      if (root.packageName?.toString() == own) continue
      return root
    }
    return svc.rootInActiveWindow?.takeIf { it.packageName?.toString() != own }
  }

  fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
    val out = ArrayList<AccessibilityNodeInfo>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    while (queue.isNotEmpty() && out.size < MAX_NODES) {
      val n = queue.removeFirst()
      out.add(n)
      for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
    }
    return out
  }

  /** Lowercased text, description, hint and view id — everything we match keywords against. */
  fun label(n: AccessibilityNodeInfo): String {
    val parts = mutableListOf(n.text, n.contentDescription, n.viewIdResourceName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) parts.add(n.hintText)
    return parts.filterNotNull().joinToString(" ").lowercase()
  }

  fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also(n::getBoundsInScreen)

  /** Nearest clickable node at or above [n]. */
  fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var cur: AccessibilityNodeInfo? = n
    var depth = 0
    while (cur != null && depth < 6) {
      if (cur.isClickable && cur.isEnabled) return cur
      cur = cur.parent
      depth++
    }
    return null
  }

  /** Cheap signature of visible text, used to tell whether the screen changed after an action. */
  fun fingerprint(root: AccessibilityNodeInfo?): Int {
    if (root == null) return 0
    return flatten(root)
      .filter { it.isVisibleToUser }
      .joinToString("|") { "${it.text ?: ""}${it.contentDescription ?: ""}" }
      .hashCode()
  }

  /** Compact, numbered description of the screen. Logged now, fed to the local model later. */
  fun describe(root: AccessibilityNodeInfo, limit: Int = 60): String =
    flatten(root)
      .filter { it.isVisibleToUser && (it.isClickable || it.isEditable || it.isScrollable || !it.text.isNullOrBlank()) }
      .take(limit)
      .mapIndexed { i, n ->
        val kind = n.className?.toString()?.substringAfterLast('.') ?: "View"
        val flags = buildList {
          if (n.isEditable) add("editable")
          if (n.isClickable) add("clickable")
          if (n.isScrollable) add("scrollable")
        }.joinToString(",")
        val text = (n.text ?: n.contentDescription ?: "").toString().take(60)
        "[$i] $kind \"$text\" $flags"
      }
      .joinToString("\n")
}
