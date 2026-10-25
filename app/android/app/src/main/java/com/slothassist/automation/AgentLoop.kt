package com.slothassist.automation

import android.util.Log
import com.slothassist.ai.AgentAction
import com.slothassist.ai.AgentPlanner
import com.slothassist.ai.ModelUnavailable

/** The agent needs the user before it can continue (login, ambiguity, a payment step…). */
class NeedsUser(val question: String) : Exception(question)

/**
 * observe → plan → validate → act → verify, one step at a time, until the model says done.
 * The model only ever proposes; the executor decides what is allowed and checks the result.
 */
class AgentLoop(
  private val goal: String,
  private val planner: AgentPlanner,
  private val exec: ActionExecutor,
  private val status: (String) -> Unit,
) {

  /** Steps that actually changed something, so the caller can report effort saved. */
  var actionsTaken = 0
    private set

  fun run(): String {
    val history = ArrayList<String>()
    var lastKey: String? = null
    var repeats = 0
    var invalid = 0

    for (step in 1..MAX_STEPS) {
      status("Step $step · reading the screen…")
      val screen = exec.observe()

      status("Step $step · thinking…")
      val action = try {
        planner.next(goal, history, screen)
      } catch (e: ModelUnavailable) {
        exec.checkCancelled() // an aborted request surfaces as a connection error
        throw e
      }
      Log.i(TAG, "step $step: $action")

      val problem = action.validate(screen)
      if (problem != null) {
        history += "$step. (invalid action: $problem)"
        if (++invalid >= MAX_INVALID) throw TaskFailed("The model kept proposing actions I can't run, so I stopped.", fatal = true)
        continue
      }

      // FR-059: same action on the same screen again and again means we're stuck.
      val key = "${screen.fingerprint}|${action.action}|${action.id}|${action.text}"
      repeats = if (key == lastKey) repeats + 1 else 0
      lastKey = key
      if (repeats >= MAX_REPEATS) throw TaskFailed("I kept trying the same thing without progress, so I stopped.", fatal = true)

      when (action.action) {
        "done" -> return action.text.ifBlank { "Done." }
        "ask_user" -> throw NeedsUser(action.text.ifBlank { "I need your help to continue." })
      }

      val what = action.describe(screen)
      status("Step $step · ${what.replaceFirstChar { it.uppercase() }}")
      exec.guard(screen.packageName)
      val result = try {
        execute(action, screen).also { actionsTaken++ }
      } catch (e: TaskFailed) {
        if (e.fatal) throw e
        "failed: ${e.message}"
      }
      history += "$step. $what -> $result"
    }
    throw TaskFailed("I reached the $MAX_STEPS-step limit before finishing.", fatal = true)
  }

  private fun execute(a: AgentAction, screen: ScreenSnapshot): String = when (a.action) {
    "search" -> exec.search(a.text)
    "tap" -> exec.tap(screen.elements[a.id], screen)
    "type" -> exec.type(screen.elements[a.id], a.text, screen)
    "scroll" -> exec.scroll(forward = a.text == "down")
    "back" -> exec.back()
    "open_url" -> exec.openUrl(a.text)
    else -> throw TaskFailed("Unknown action ${a.action}")
  }

  companion object {
    private const val TAG = "SlothAgent"
    const val MAX_STEPS = 15
    private const val MAX_REPEATS = 2
    private const val MAX_INVALID = 3
  }
}
