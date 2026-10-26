package com.slothassist.automation

import android.util.Log

/**
 * Built-in skills for sub-tasks with a known shape. They run without the model, act on the live
 * screen, and confirm their own result from the screen and the event journal — so "done" is
 * decided by evidence, not by the model's opinion.
 */
sealed class Skill {
  data class Search(val query: String) : Skill()
  data class AddToCart(val nth: Int, val quantity: Int) : Skill()
  data class SetQuantity(val quantity: Int) : Skill()

  companion object {
    private val SEARCH = Regex("^(please\\s+)?(search|look up|look for|find)\\s+(for\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val ORDINAL = "(\\d+)(?:st|nd|rd|th)?|first|second|third|fourth|fifth|one|two|three"
    private val ADD = Regex(
      "^add\\s+(?:(\\d+|one|two|three|four|five)\\s+(?:quantit(?:y|ies)\\s+|units?\\s+|packs?\\s+|pieces?\\s+)?(?:of\\s+)?)?" +
        "(?:the\\s+)?($ORDINAL)\\s+(?:option|item|result|product)s?\\s+(?:to\\s+(?:the\\s+|my\\s+)?(?:cart|kart|basket|bag))?.*$",
      RegexOption.IGNORE_CASE,
    )
    private val QUANTITY = Regex(
      "^(?:increase|set|change|make|update)\\s+(?:its|the|it|item)?\\s*(?:quantity|qty|count)?\\s*(?:to|as)\\s+(\\d+|one|two|three|four|five).*$",
      RegexOption.IGNORE_CASE,
    )
    private val WORDS = mapOf("one" to 1, "first" to 1, "two" to 2, "second" to 2, "three" to 3, "third" to 3, "four" to 4, "fourth" to 4, "five" to 5, "fifth" to 5)

    private fun number(s: String?): Int? = s?.lowercase()?.let { WORDS[it] ?: it.filter(Char::isDigit).toIntOrNull() }

    /** The skill that handles [subTask], or null to let the planner handle it. */
    fun match(subTask: String): Skill? {
      val t = subTask.trim().trimEnd('.')
      ADD.matchEntire(t)?.let { m ->
        val nth = number(m.groupValues[2]) ?: return@let
        return AddToCart(nth, number(m.groupValues[1]) ?: 1)
      }
      QUANTITY.matchEntire(t)?.let { m -> number(m.groupValues[1])?.let { return SetQuantity(it) } }
      SEARCH.matchEntire(t)?.let { m -> m.groupValues[4].trim().takeIf { it.isNotBlank() }?.let { return Search(it) } }
      return null
    }
  }
}

/** Runs [Skill]s. Remembers the product it added so a later "increase its quantity" finds the same card. */
class SkillRunner(private val exec: ActionExecutor, private val status: (String) -> Unit) {

  /** Label of the product card the last add_to_cart acted on. */
  private var focusCard: String? = null

  fun run(skill: Skill): String = when (skill) {
    is Skill.Search -> exec.search(skill.query)
    is Skill.AddToCart -> addToCart(skill.nth, skill.quantity)
    is Skill.SetQuantity -> setQuantity(skill.quantity)
  }

  private fun addToCart(nth: Int, quantity: Int): String {
    status("Adding option $nth to the cart…")
    // Result lists render progressively: wait until the nth product card is actually there.
    val screen = exec.observeUntil(5_000) { productCards(it).size >= nth }
    val products = productCards(screen)
    val card = products.getOrNull(nth - 1)
      ?: throw TaskFailed(if (products.isEmpty()) "I don't see any products with an ADD button here." else "I only see ${products.size} products on screen.")
    focusCard = card.label
    val inCard = screen.inside(card)
    Log.i(TAG, "add_to_cart #$nth -> ${card.label}")

    // Already in the cart (stepper instead of ADD): nothing to add, just adjust the quantity.
    if (inCard.any { isPlus(it.label) }) {
      val now = countFor(screen)
      val base = "\"${card.label}\" is already in the cart" + (now?.let { " (quantity $it)" } ?: "")
      return if (quantity > 1 || now == null) "$base; ${setQuantity(maxOf(quantity, 1))}" else base
    }
    val add = inCard.first { isAddButton(it) }

    val mark = EventJournal.mark()
    exec.tap(add, screen)
    // ADD turns into a − 1 + stepper (or opens a pack-size picker) a moment later.
    var after = exec.observeUntil(3_000) { countFor(it) != null || it.topLayer != null }

    // Some products open a size/pack picker first: take its first ADD.
    if (after.topLayer != null || after.elements.none { isStepperOf(after, it) }) {
      after.elements.firstOrNull { isAddButton(it) && after.topLayer != null }?.let { pick ->
        status("Picking the first pack size…")
        exec.tap(pick, after)
        after = exec.observeUntil(3_000) { countFor(it) != null }
      }
    }

    val count = countFor(after)
    val events = EventJournal.since(mark)
    val confirmed = count != null || events.any { it.contains("add", ignoreCase = true) || it.contains("cart", ignoreCase = true) }
    if (!confirmed) throw TaskFailed("I tapped ADD, but couldn't confirm the item went into the cart.")
    val base = "added \"${card.label}\" to the cart" + (count?.let { " (quantity $it)" } ?: "")
    return if (quantity > 1) "$base; ${setQuantity(quantity)}" else base
  }

  private fun setQuantity(target: Int): String {
    repeat(target + 2) {
      val screen = exec.observe()
      val count = countFor(screen) ?: throw TaskFailed("I can't see a quantity counter for this item.")
      if (count == target) return "quantity is now $target"
      val stepper = stepperButtons(screen) ?: throw TaskFailed("I can't find the + / − buttons for this item.")
      val button = if (count < target) stepper.second else stepper.first
      status("Quantity $count → $target…")
      exec.tap(button, screen)
      exec.observeUntil(2_000) { countFor(it) != count }
    }
    val final = countFor(exec.observe())
    if (final == target) return "quantity is now $target"
    throw TaskFailed("I tried to set the quantity to $target, but it shows ${final ?: "nothing"}.")
  }

  // ---------- reading the product card ----------

  /** Product cards in reading order: cards with an ADD button or a − / + stepper, ads and sponsored excluded. */
  private fun productCards(screen: ScreenSnapshot): List<UiElement> =
    screen.elements.filter { it.kind == "item" }
      .filter { card -> screen.inside(card).any { isAddButton(it) || isPlus(it.label) } }
      .filterNot { SPONSORED.containsMatchIn(it.label) }

  /** The card we're working on: the one we added, else the first card with a stepper. */
  private fun focus(screen: ScreenSnapshot): UiElement? {
    val cards = screen.elements.filter { it.kind == "item" }
    focusCard?.let { label -> cards.firstOrNull { it.label == label }?.let { return it } }
    return cards.firstOrNull { card -> screen.inside(card).any { isStepperOf(screen, it) } }
  }

  /** (−, +) for the focused card, or the first stepper on screen. */
  private fun stepperButtons(screen: ScreenSnapshot): Pair<UiElement, UiElement>? {
    val scope = focus(screen)?.let(screen::inside) ?: screen.elements
    val plus = scope.firstOrNull { isPlus(it.label) } ?: return null
    val minus = scope.firstOrNull { isMinus(it.label) } ?: return null
    return minus to plus
  }

  /** The number shown between − and + for the focused card. */
  private fun countFor(screen: ScreenSnapshot): Int? {
    val scope = focus(screen)?.let(screen::inside) ?: screen.elements
    val plus = scope.firstOrNull { isPlus(it.label) } ?: return null
    // The counter sits on the same row as +, just to its left.
    return scope
      .filter { it.label.trim().matches(Regex("\\d{1,2}")) && kotlin.math.abs(it.bounds.centerY() - plus.bounds.centerY()) < plus.bounds.height() }
      .minByOrNull { kotlin.math.abs(it.bounds.centerX() - plus.bounds.centerX()) }
      ?.label?.trim()?.toIntOrNull()
  }

  private fun isStepperOf(screen: ScreenSnapshot, e: UiElement) = isPlus(e.label) || isMinus(e.label)

  private fun isAddButton(e: UiElement) =
    e.kind != "text" && e.label.trim().lowercase().let { it == "add" || it == "add to cart" || it.startsWith("add ") && it.length <= 14 }


  private fun isPlus(label: String) = label.trim().lowercase().let { it == "+" || it.startsWith("increase") || it.startsWith("increment") || it == "add one" }
  private fun isMinus(label: String) = label.trim().lowercase().let { it == "-" || it == "−" || it.startsWith("decrease") || it.startsWith("decrement") || it == "remove one" }

  companion object {
    private const val TAG = "SlothSkill"
    /** Ads and sponsored listings don't count as "the first option". */
    private val SPONSORED = Regex("\\b(ad|sponsored|promoted)\\b", RegexOption.IGNORE_CASE)
  }
}
