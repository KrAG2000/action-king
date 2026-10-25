package com.slothassist.ai

import com.slothassist.automation.ScreenSnapshot
import com.slothassist.automation.TaskState
import org.json.JSONArray
import org.json.JSONObject

/**
 * One action inside a chunk. [target] is the element's visible label rather than a screen number,
 * so steps after a predictable screen change (e.g. "+" appearing after "ADD") still resolve
 * without another model call.
 */
data class Step(val action: String, val target: String, val nth: Int, val text: String) {

  /** Why this step can't run, or null if it's well-formed (FR-063/064). */
  fun problem(): String? = when (action) {
    "tap" -> if (target.isBlank()) "tap needs a target" else null
    "type" -> if (target.isBlank() || text.isBlank()) "type needs a target and text" else null
    "search", "open_url" -> if (text.isBlank()) "$action needs text" else null
    "scroll" -> if (text !in listOf("up", "down")) "scroll text must be up or down" else null
    "back" -> null
    else -> "unknown action \"$action\""
  }

  override fun toString(): String = when (action) {
    "tap" -> "tap \"$target\"" + if (nth > 1) " (#$nth)" else ""
    "type" -> "type \"$text\" into \"$target\""
    "search" -> "search \"$text\""
    "scroll" -> "scroll $text"
    "open_url" -> "open $text"
    else -> action
  }
}

/** The planner's judgement of the current state plus the next predictable run of steps. */
data class Chunk(val status: String, val note: String, val steps: List<Step>) {
  companion object {
    const val WORKING = "working"
    const val SUBTASK_DONE = "subtask_done"
    const val ALL_DONE = "all_done"
    const val NEED_USER = "need_user"
  }
}

/** Talks to the model: splits the goal once, then assesses and plans one chunk per checkpoint. */
class AgentPlanner(private val client: ChatModel) {

  fun split(goal: String): List<String> {
    val reply = runCatching { client.chat(SPLIT_PROMPT, goal, SPLIT_SCHEMA, SPLIT_REGEX) }.getOrNull()
    val steps = reply?.optJSONArray("steps")
      ?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() } }
      ?.filter { it.isNotBlank() }
    return steps?.takeIf { it.isNotEmpty() } ?: listOf(goal)
  }

  fun plan(state: TaskState, screen: ScreenSnapshot): Chunk {
    val user = state.render() + "\n" + screen.describe() + "\nJudge the current sub-task and give the next steps."
    val reply = client.chat(CHUNK_PROMPT, user, CHUNK_SCHEMA, CHUNK_REGEX)
    val arr = reply.optJSONArray("steps") ?: JSONArray()
    val steps = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
      Step(
        action = it.optString("do"),
        target = it.optString("target").trim(),
        nth = it.optInt("nth", 1).coerceAtLeast(1),
        text = it.optString("text").trim(),
      )
    }
    return Chunk(reply.optString("status", Chunk.WORKING), reply.optString("note").trim(), steps.take(MAX_CHUNK))
  }

  companion object {
    const val MAX_CHUNK = 5
    private val ACTIONS = listOf("tap", "type", "search", "scroll", "back", "open_url")
    private val STATUSES = listOf(Chunk.WORKING, Chunk.SUBTASK_DONE, Chunk.ALL_DONE, Chunk.NEED_USER)

    // ---------- split ----------

    private val SPLIT_PROMPT = """
      Split the user's phone command into short sub-tasks, in order. Each sub-task is one thing to achieve on screen.
      Keep the user's own words, names and quantities. A simple command stays as one sub-task.
      Example: "search for rice 1 kg and add 2 of the first option to the cart" ->
      {"steps":["search for rice 1 kg","add the first option to the cart","increase its quantity to 2"]}
    """.trimIndent()

    private val SPLIT_SCHEMA: JSONObject = JSONObject()
      .put("type", "object")
      .put("properties", JSONObject().put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))))
      .put("required", JSONArray(listOf("steps")))

    private const val SPLIT_REGEX = """\{"steps":\["[^"\\\n]{1,80}"(,"[^"\\\n]{1,80}"){0,5}\]\}"""

    // ---------- chunk ----------

    val CHUNK_PROMPT = """
      You plan for SlothAssist, an agent that operates an Android phone for a lazy user.
      You get the goal, a checklist of sub-tasks, a log of what was already done (with verified results), and the current screen.
      Each turn, reply with:
      1. status, judged from the screen:
         "working" = the current sub-task is not finished yet.
         "subtask_done" = the current sub-task is visibly achieved; give steps for the next sub-task.
         "all_done" = every sub-task is achieved; no steps.
         "need_user" = login, OTP, CAPTCHA, payment or an unclear request; put the question in note; no steps.
      2. note: one short sentence about what you see or intend.
      3. steps: the next actions you can predict from this screen, up to the point where a new page loads and you can't know what comes next. Usually 1 to 4 steps.
      Step fields:
      - do: tap | type | search | scroll | back | open_url
      - target: for tap and type, the element's visible label copied from the screen list. For an element that will appear after an earlier step (like "+" after "ADD"), write its usual label. Otherwise "".
      - nth: which match to use when several elements share the label, 1 = first in reading order.
      - text: the text to type or search for, "down" or "up" for scroll, the web address for open_url, otherwise "".
      Rules:
      - To search, use do "search" with the query. It finds the search bar by itself.
      - The first result or option is the first one in reading order. Skip ads, "Sponsored" items and filters.
      - Quantity N in a shopping app: tap "ADD" once, then tap "+" N-1 times, in the same steps list.
      - Close pop-ups that are in the way first ("No thanks", "Not now", "Close").
      - Never pay, place an order or check out.
      - Do not repeat steps the log shows as failed or with no effect; try something else.
      Example: {"status":"working","note":"Results are showing; add the first one twice.","steps":[{"do":"tap","target":"ADD","nth":1,"text":""},{"do":"tap","target":"+","nth":1,"text":""}]}
      Reply only with JSON.
    """.trimIndent()

    private val STEP_SCHEMA: JSONObject = JSONObject()
      .put("type", "object")
      .put("properties", JSONObject()
        .put("do", JSONObject().put("type", "string").put("enum", JSONArray(ACTIONS)))
        .put("target", JSONObject().put("type", "string"))
        .put("nth", JSONObject().put("type", "integer"))
        .put("text", JSONObject().put("type", "string")))
      .put("required", JSONArray(listOf("do", "target", "nth", "text")))

    private val CHUNK_SCHEMA: JSONObject = JSONObject()
      .put("type", "object")
      .put("properties", JSONObject()
        .put("status", JSONObject().put("type", "string").put("enum", JSONArray(STATUSES)))
        .put("note", JSONObject().put("type", "string"))
        .put("steps", JSONObject().put("type", "array").put("items", STEP_SCHEMA)))
      .put("required", JSONArray(listOf("status", "note", "steps")))

    /** Compact single-line form for small on-device models: no whitespace to ramble with. */
    private val STEP_REGEX =
      """\{"do":"(${ACTIONS.joinToString("|")})","target":"[^"\\\n]{0,60}","nth":[1-9],"text":"[^"\\\n]{0,80}"\}"""
    private val CHUNK_REGEX =
      """\{"status":"(${STATUSES.joinToString("|")})","note":"[^"\\\n]{0,100}","steps":\[($STEP_REGEX(,$STEP_REGEX){0,${MAX_CHUNK - 1}})?\]\}"""
  }
}
