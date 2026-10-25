package com.slothassist.automation

import android.util.Log
import com.slothassist.ai.AgentPlanner
import com.slothassist.ai.Chunk
import com.slothassist.ai.ModelUnavailable
import com.slothassist.ai.Step

/** The agent needs the user before it can continue (login, ambiguity, a payment step…). */
class NeedsUser(val question: String) : Exception(question)

/**
 * The harness. The goal is split into a checklist once; then each round is:
 *   checkpoint (one model call: judge progress + plan the next predictable chunk)
 *   → execute the whole chunk fast, resolving each step by label on the live screen and verifying it
 *   → stop the chunk early at the first surprise, record everything in [TaskState], repeat.
 * Known sub-task shapes ("search for X") run as built-in skills with no model call at all.
 */
class AgentLoop(
  private val goal: String,
  private val planner: AgentPlanner,
  private val exec: ActionExecutor,
  private val status: (String) -> Unit,
) {

  /** Actions actually performed, so the caller can report effort saved. */
  var actionsTaken = 0
    private set

  private var expectedApp: String? = null

  fun run(): String {
    status("Planning…")
    val state = TaskState(goal, modelCall { planner.split(goal) })
    Log.i(TAG, "checklist: ${state.checklist}")

    var lastSignature: String? = null
    var stalls = 0

    for (round in 1..MAX_ROUNDS) {
      if (actionsTaken >= MAX_ACTIONS) break
      val label = "Round $round · ${state.current + 1}/${state.checklist.size}"
      val screen = observeLocked()

      // Built-in skill: a plain search needs no model.
      val query = searchQuery(state.currentSubTask)
      val chunk = if (query != null) {
        Chunk(Chunk.WORKING, "search skill", listOf(Step("search", "", 1, query)))
      } else {
        status("$label · thinking…")
        modelCall { planner.plan(state, screen) }
      }
      Log.i(TAG, "round $round [${state.currentSubTask}] ${chunk.status}: ${chunk.note} -> ${chunk.steps}")

      when (chunk.status) {
        Chunk.ALL_DONE -> return finish(state)
        Chunk.NEED_USER -> throw NeedsUser(chunk.note.ifBlank { "I need your help to continue." })
        Chunk.SUBTASK_DONE -> {
          if (state.advance()) return finish(state)
          // The model's steps are for the next sub-task, unless a built-in skill covers it.
          if (searchQuery(state.currentSubTask) != null) continue
        }
      }

      if (chunk.steps.isEmpty()) {
        state.record("(planner gave no steps)")
        if (++stalls >= MAX_STALLS) throw TaskFailed("I couldn't work out the next step, so I stopped.", fatal = true)
        continue
      }

      // FR-059: the same plan on the same screen again means we're going round in circles.
      val signature = "${screen.fingerprint}|${chunk.steps}"
      if (signature == lastSignature) {
        if (++stalls >= MAX_STALLS) throw TaskFailed("I kept trying the same thing without progress, so I stopped.", fatal = true)
      }
      lastSignature = signature

      val completed = runChunk(chunk.steps, screen, label, state)
      if (query != null && completed) {
        if (state.advance()) return "Searched for \"$query\""
      }
    }
    throw TaskFailed("I reached my step limit before finishing.", fatal = true)
  }

  /**
   * Executes steps back to back. Each step is resolved against the live screen (by label), so a
   * chunk can run across predictable screen changes. Returns true if every step ran and had an effect.
   */
  private fun runChunk(steps: List<Step>, first: ScreenSnapshot, label: String, state: TaskState): Boolean {
    var screen = first
    for ((i, step) in steps.withIndex()) {
      if (i > 0) screen = observeLocked()
      step.problem()?.let {
        state.record("✗ $step: $it")
        return false
      }
      status("$label · ${step.toString().replaceFirstChar { it.uppercase() }}")
      val result = try {
        perform(step, screen).also { actionsTaken++ }
      } catch (e: TaskFailed) {
        if (e.fatal) throw e
        state.record("✗ $step: ${e.message}")
        Log.i(TAG, "  ✗ $step: ${e.message}")
        return false
      }
      Log.i(TAG, "  ✓ $step: $result")
      state.record("✓ $step: $result")
      expectedApp = exec.observe().packageName
      if (result.contains("nothing changed")) return false // a surprise: stop and reassess
    }
    return true
  }

  private fun perform(step: Step, screen: ScreenSnapshot): String = when (step.action) {
    "search" -> exec.search(step.text)
    "scroll" -> exec.scroll(forward = step.text == "down")
    "back" -> exec.back()
    "open_url" -> exec.openUrl(step.text)
    "tap", "type" -> {
      val el = TargetResolver.resolve(step.target, step.nth, screen)
        ?: throw TaskFailed("couldn't find \"${step.target}\" on screen")
      when {
        step.action == "tap" -> exec.tap(el, screen)
        // Typing into a search or address bar means "search for this".
        exec.isSearchBox(el) -> exec.search(step.text)
        else -> exec.type(el, step.text, screen)
      }
    }
    else -> throw TaskFailed("unknown action ${step.action}")
  }

  /** FR-046/047: the foreground app may only change because of something we did. */
  private fun observeLocked(): ScreenSnapshot {
    val screen = exec.observe()
    val expected = expectedApp
    if (expected != null && screen.packageName != expected) throw TaskFailed("You switched apps, so I stopped.", fatal = true)
    expectedApp = screen.packageName
    return screen
  }

  private fun finish(state: TaskState) = "Done: ${state.goal}"

  private fun <T> modelCall(block: () -> T): T = try {
    block()
  } catch (e: ModelUnavailable) {
    exec.checkCancelled() // an aborted request surfaces as a connection error
    throw e
  }

  companion object {
    private const val TAG = "SlothAgent"
    private const val MAX_ROUNDS = 10
    private const val MAX_ACTIONS = 25
    private const val MAX_STALLS = 2
    private val SEARCH_TASK = Regex("^(please\\s+)?(search|look up|look for|find)\\s+(for\\s+)?(.+)$", RegexOption.IGNORE_CASE)

    /** The query if a sub-task is a plain search ("search for rice 1 kg"), else null. */
    fun searchQuery(subTask: String): String? =
      SEARCH_TASK.matchEntire(subTask.trim())?.groupValues?.get(4)?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }
  }
}
