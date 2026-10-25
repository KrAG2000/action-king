package com.slothassist.ai

import com.slothassist.automation.ScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** One step proposed by the model. Only these verbs exist; the model can't run anything else (FR-063). */
data class AgentAction(val thought: String, val action: String, val id: Int, val text: String) {

  /** Returns why the action can't run on [screen], or null if it's valid (FR-064). */
  fun validate(screen: ScreenSnapshot): String? = when (action) {
    "tap" -> if (id !in screen.elements.indices) "tap needs an element id from the screen list" else null
    "type" -> when {
      id !in screen.elements.indices -> "type needs an element id from the screen list"
      !screen.elements[id].node.isEditable -> "element $id is not a text box"
      text.isBlank() -> "type needs text"
      else -> null
    }
    "search", "open_url" -> if (text.isBlank()) "$action needs text" else null
    "scroll" -> if (text !in listOf("up", "down")) "scroll text must be \"up\" or \"down\"" else null
    "back", "done", "ask_user" -> null
    else -> "unknown action \"$action\""
  }

  /** Short, human description for the status pill and the history fed back to the model. */
  fun describe(screen: ScreenSnapshot): String = when (action) {
    "tap" -> "tap [$id] \"${screen.elements.getOrNull(id)?.label?.take(40) ?: "?"}\""
    "type" -> "type \"$text\" into [$id]"
    "search" -> "search \"$text\""
    "scroll" -> "scroll $text"
    "open_url" -> "open $text"
    else -> action
  }
}

/** Builds the prompt for the next step and parses the model's reply. */
class AgentPlanner(private val client: OllamaClient) {

  fun next(goal: String, history: List<String>, screen: ScreenSnapshot): AgentAction {
    val user = buildString {
      append("Goal: ").append(goal).append("\n\n")
      append("Steps so far:\n")
      if (history.isEmpty()) append("(none yet)\n") else history.takeLast(MAX_HISTORY).forEach { append(it).append('\n') }
      append('\n').append(screen.describe())
      append("\nWhat is the next action?")
    }
    val reply = client.chat(SYSTEM_PROMPT, user, SCHEMA)
    return AgentAction(
      thought = reply.optString("thought"),
      action = reply.optString("action"),
      id = reply.optInt("id", -1),
      text = reply.optString("text").trim(),
    )
  }

  companion object {
    private const val MAX_HISTORY = 12

    private val ACTIONS = listOf("search", "tap", "type", "scroll", "back", "open_url", "done", "ask_user")

    private val SCHEMA: JSONObject = JSONObject()
      .put("type", "object")
      .put("properties", JSONObject()
        .put("thought", JSONObject().put("type", "string"))
        .put("action", JSONObject().put("type", "string").put("enum", JSONArray(ACTIONS)))
        .put("id", JSONObject().put("type", "integer"))
        .put("text", JSONObject().put("type", "string")))
      .put("required", JSONArray(listOf("thought", "action", "id", "text")))

    val SYSTEM_PROMPT = """
      You are SlothAssist, an agent that operates an Android phone for a lazy user, one action at a time.
      Each turn you get the user's goal, the actions already taken with their results, and the current screen as numbered elements.
      Choose the single best next action:
      - search: search inside the current app. Put the query in text. It finds the search bar, types and submits by itself.
      - tap: tap element id. Use for buttons, links, products, tabs, ADD and + buttons.
      - type: type text into the editable element id (replaces its content).
      - scroll: scroll the screen. text is "down" or "up". Use when what you need is not visible.
      - back: press Android back.
      - open_url: open the web address in text.
      - done: the whole goal is visibly achieved on screen. Put a short summary for the user in text.
      - ask_user: you need the user (ambiguous request, login, OTP, CAPTCHA, payment). Put the question in text.
      Rules:
      - Do the goal's steps in order. Do not redo steps already done successfully.
      - "1st option" / "first result" is the first product or result in reading order (top to bottom, left to right), not an ad or filter.
      - To set a quantity of N in a shopping app: tap ADD once, then tap "+" until the screen shows N. Check the count on screen.
      - To open a link on a web page, tap it. Use open_url only for a web address the user gave.
      - Never tap anything that pays, places an order, or checks out.
      - If an action had no effect twice, try something different or ask_user.
      - Before done, make sure the screen shows the goal is achieved.
      Reply only with JSON: {"thought": one short sentence, "action": ..., "id": element number or -1, "text": string or ""}.
    """.trimIndent()
  }
}
