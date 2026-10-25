package com.slothassist.automation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.slothassist.ai.AgentConfig
import com.slothassist.ai.AgentPlanner
import com.slothassist.ai.ChatModel
import com.slothassist.ai.GemmaOnDevice
import com.slothassist.ai.ModelUnavailable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Status shown in the floating panel. */
data class TaskStatus(val state: State, val message: String) {
  enum class State { IDLE, RUNNING, DONE, FAILED, NEEDS_USER }
}

/** Runs one command at a time off the main thread: the local-model agent, or simple rules as a fallback. */
object TaskRunner {

  private const val TAG = "SlothTask"
  private const val TASK_TIMEOUT_MS = 180_000L

  private val executor = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())
  private val cancelFlag = AtomicBoolean(false)
  @Volatile private var running = false
  @Volatile private var client: ChatModel? = null

  /** Set while the agent is waiting for the user's answer; the next command is treated as that answer. */
  private var pending: Pair<String, String>? = null

  var listener: ((TaskStatus) -> Unit)? = null

  val isRunning get() = running
  val awaitingAnswer get() = pending != null

  fun cancel() {
    if (!running) {
      pending = null
      return
    }
    cancelFlag.set(true)
    client?.abort()
  }

  fun run(command: String) {
    if (running) return publish(TaskStatus(TaskStatus.State.FAILED, "Already working on something. Tap Stop first."))
    val svc = AssistantAccessibilityService.instance
      ?: return publish(TaskStatus(TaskStatus.State.FAILED, "Turn on the SlothAssist accessibility service first."))

    val goal = pending?.let { (g, q) -> "$g\n(You asked the user: \"$q\". They answered: \"$command\")" } ?: command
    pending = null
    running = true
    cancelFlag.set(false)
    val deadline = System.currentTimeMillis() + TASK_TIMEOUT_MS
    publish(TaskStatus(TaskStatus.State.RUNNING, "Working on: $command"))

    executor.execute {
      val exec = ActionExecutor(
        svc,
        cancelled = { cancelFlag.get() || System.currentTimeMillis() > deadline },
        status = { publish(TaskStatus(TaskStatus.State.RUNNING, it)) },
      )
      val model = AgentConfig.load(svc).createModel(svc).also { client = it }
      (model as? GemmaOnDevice)?.onLoading = { publish(TaskStatus(TaskStatus.State.RUNNING, it)) }
      val loop = AgentLoop(goal, AgentPlanner(model), exec) { publish(TaskStatus(TaskStatus.State.RUNNING, it)) }
      val started = System.currentTimeMillis()

      val result = try {
        val summary = loop.run()
        val secs = (System.currentTimeMillis() - started) / 1000
        TaskStatus(TaskStatus.State.DONE, "✓ $summary\n${loop.actionsTaken} actions in ${secs}s")
      } catch (e: NeedsUser) {
        pending = goal to e.question
        TaskStatus(TaskStatus.State.NEEDS_USER, "🙋 ${e.question}")
      } catch (e: ModelUnavailable) {
        fallback(command, loop.actionsTaken, e, exec)
      } catch (e: TaskFailed) {
        TaskStatus(TaskStatus.State.FAILED, e.message ?: "Something went wrong.")
      } catch (e: Exception) {
        Log.e(TAG, "Task crashed", e)
        TaskStatus(TaskStatus.State.FAILED, "Something went wrong: ${e.message}")
      }
      client = null
      running = false
      Log.i(TAG, "\"$command\" -> ${result.state}: ${result.message}")
      publish(result)
    }
  }

  /** Without the model, a single simple command can still run on rules (graceful degradation). */
  private fun fallback(command: String, actionsTaken: Int, cause: ModelUnavailable, exec: ActionExecutor): TaskStatus {
    val simple = actionsTaken == 0 && !Regex("\\b(and|then)\\b|,").containsMatchIn(command.lowercase())
    if (!simple) return TaskStatus(TaskStatus.State.FAILED, cause.message ?: "The local model isn't available.")
    return try {
      TaskStatus(TaskStatus.State.DONE, "✓ ${CommandRouter.route(command, exec)}\n(model offline, used simple mode)")
    } catch (e: TaskFailed) {
      TaskStatus(TaskStatus.State.FAILED, "${e.message}\n(${cause.message})")
    }
  }

  private fun publish(s: TaskStatus) {
    main.post { listener?.invoke(s) }
  }
}

/** Rule-based commands, used when the local model can't be reached. */
object CommandRouter {

  private val SEARCH_PREFIX = Regex("^(please\\s+)?(search\\s+(for\\s+)?|find\\s+|look\\s+(up|for)\\s+)", RegexOption.IGNORE_CASE)

  fun route(command: String, exec: ActionExecutor): String {
    val text = command.trim().trimEnd('.', '!')
    val lower = text.lowercase()
    return when {
      lower.startsWith("scroll up") -> exec.scroll(forward = false)
      lower.startsWith("scroll") -> exec.scroll(forward = true)
      lower == "back" || lower == "go back" -> exec.back()
      else -> exec.search(text.replace(SEARCH_PREFIX, "").trim())
    }
  }
}
