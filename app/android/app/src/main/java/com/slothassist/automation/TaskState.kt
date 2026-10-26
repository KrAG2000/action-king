package com.slothassist.automation

/**
 * The harness's memory for one task: what the goal is, which sub-task is current,
 * and what has been done so far with verified outcomes. Rendered into every planning call.
 */
class TaskState(val goal: String, val checklist: List<String>) {

  var current = 0
    private set

  private val log = ArrayList<String>()

  val currentSubTask get() = checklist[current]
  val finished get() = current >= checklist.size

  /** Marks the current sub-task achieved; returns true when that was the last one. */
  fun advance(): Boolean {
    log += "✓ sub-task done: ${checklist[current]}"
    current++
    return finished
  }

  fun record(entry: String) {
    log += if (entry.length > MAX_ENTRY) entry.take(MAX_ENTRY) + "…" else entry
  }

  fun render(): String = buildString {
    append("Goal: ").append(goal).append('\n')
    append("Checklist:\n")
    checklist.forEachIndexed { i, s ->
      append(i + 1).append(". ").append(s)
      append(
        when {
          i < current -> "  ✓ done"
          i == current -> "  ← current"
          else -> ""
        }
      ).append('\n')
    }
    append("Done so far:\n")
    if (log.isEmpty()) append("(nothing yet)\n") else log.takeLast(MAX_LOG).forEach { append("- ").append(it).append('\n') }
  }

  companion object {
    private const val MAX_LOG = 10
    private const val MAX_ENTRY = 180
  }
}

/** Finds the element a step means by its label, on whatever the screen shows right now. */
object TargetResolver {

  /** Labels apps use for the same control. */
  private val SYNONYMS = mapOf(
    "+" to listOf("+", "increase", "increment", "add one", "plus", "add more"),
    "-" to listOf("-", "−", "decrease", "decrement", "remove one", "minus"),
    "add" to listOf("add", "add to cart", "add to basket"),
    "close" to listOf("close", "dismiss", "×", "✕", "x"),
  )

  /**
   * [planned] is the screen the model was looking at. A bare number ("10", "[10]") means that element
   * number, which only holds while the screen hasn't changed since.
   */
  fun resolve(target: String, nth: Int, screen: ScreenSnapshot, planned: ScreenSnapshot): UiElement? {
    val t = normalize(target)
    if (t.isEmpty()) return null
    NUMBER.matchEntire(t)?.let { m ->
      if (screen.fingerprint != planned.fingerprint) return null
      return screen.elements.getOrNull(m.groupValues[1].toInt())
    }
    val alts = SYNONYMS[t] ?: listOf(t)
    val labelled = screen.elements.map { it to normalize(it.label) }

    // Best match tier wins: exact label, then starts with, then contains. Interactive elements before plain text.
    val tiers = listOf<(String) -> Boolean>(
      { l -> alts.any { l == it } },
      { l -> alts.any { (it.length >= 3 || !it[0].isLetterOrDigit()) && l.startsWith(it) } },
      { l -> alts.any { it.length >= 3 && l.contains(it) } },
    )
    for (matches in tiers) {
      val hits = labelled.filter { (_, l) -> matches(l) }.map { it.first }
        .sortedBy { if (it.kind == "text") 1 else 0 }
      if (hits.isNotEmpty()) return hits.getOrNull(nth - 1)
    }
    return null
  }

  private val NUMBER = Regex("^\\[?#?(\\d{1,3})\\]?$")

  private fun normalize(s: String) = s.lowercase().trim().trim('"', '\'', '.').replace(Regex("\\s+"), " ")
}
