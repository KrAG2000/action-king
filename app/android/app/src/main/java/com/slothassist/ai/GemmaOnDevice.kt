package com.slothassist.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import org.json.JSONObject
import java.io.File

/**
 * Gemma running inside SlothAssist via LiteRT-LM — no server, no network.
 * The engine (~2.6 GB) is loaded on first use, kept warm across a task's steps,
 * and released after [IDLE_UNLOAD_MS] so it doesn't sit in memory while the user just browses.
 */
class GemmaOnDevice private constructor(private val ctx: Context, private val modelFile: File) : ChatModel {

  private val lock = Any()
  private var engine: Engine? = null
  @Volatile private var active: Conversation? = null
  @Volatile var backendName: String = "-"
    private set

  /** Called before each chat so the UI can say "loading" the first time. */
  var onLoading: ((String) -> Unit)? = null

  override fun chat(system: String, user: String, schema: JSONObject, compactRegex: String?): JSONObject {
    synchronized(lock) {
      main.removeCallbacks(unload)
      try {
        val eng = engineOrLoad()
        val config = ConversationConfig(
          systemInstruction = Contents.of(system),
          // Greedy decoding: the same screen should give the same action.
          samplerConfig = SamplerConfig(1, 1.0, 0.0, 0),
          maxOutputToken = MAX_OUTPUT_TOKENS,
          enableResponseFormat = true, // constrain the reply to the action JSON schema
        )
        val started = SystemClock.elapsedRealtime()
        val text = eng.createConversation(config).use { conversation ->
          active = conversation
          try {
            val format = compactRegex?.let(ResponseFormat::regex) ?: ResponseFormat.json(schema.toString())
            val reply = conversation.sendMessage(Contents.of(user), responseFormat = format)
            reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
          } finally {
            active = null
          }
        }
        Log.i(TAG, "gemma ($backendName) replied in ${SystemClock.elapsedRealtime() - started} ms: $text")
        return parseJson(text)
      } finally {
        main.postDelayed(unload, IDLE_UNLOAD_MS)
      }
    }
  }

  override fun abort() {
    active?.cancelProcess()
  }

  /** Loads the engine and runs a tiny prompt; returns a short human-readable timing summary. */
  fun warmUp(): String {
    val started = SystemClock.elapsedRealtime()
    chat("Reply with JSON.", "Say ok.", JSONObject("""{"type":"object","properties":{"ok":{"type":"string"}},"required":["ok"]}"""))
    return "Gemma ready on $backendName (${(SystemClock.elapsedRealtime() - started) / 100 / 10.0}s)"
  }

  private fun engineOrLoad(): Engine {
    engine?.let { return it }
    if (!modelFile.exists()) {
      throw ModelUnavailable(
        "Gemma isn't on this phone yet. Copy ${modelFile.name} to ${modelFile.parent} (see the setup screen)."
      )
    }
    // GPU is several times faster; some phones lack OpenCL, so fall back to CPU.
    for ((name, backend) in listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())) {
      onLoading?.invoke("Loading Gemma on the $name… (first time takes a bit)")
      val candidate = Engine(EngineConfig(
        modelPath = modelFile.absolutePath,
        backend = backend,
        maxNumTokens = MAX_TOKENS,
        cacheDir = ctx.cacheDir.path, // makes every load after the first much faster
      ))
      try {
        val started = SystemClock.elapsedRealtime()
        candidate.initialize()
        Log.i(TAG, "Gemma loaded on $name in ${SystemClock.elapsedRealtime() - started} ms")
        backendName = name
        engine = candidate
        return candidate
      } catch (e: Exception) {
        Log.w(TAG, "Gemma failed to load on $name", e)
        runCatching { candidate.close() }
      }
    }
    throw ModelUnavailable("Gemma couldn't be loaded on this phone (GPU and CPU both failed).")
  }

  private val unload = Runnable {
    synchronized(lock) {
      engine?.let {
        Log.i(TAG, "Unloading idle Gemma")
        runCatching { it.close() }
      }
      engine = null
    }
  }

  companion object {
    private const val TAG = "SlothGemma"
    private const val MAX_TOKENS = 4096 // system prompt + screen + reply
    private const val MAX_OUTPUT_TOKENS = 120
    private const val IDLE_UNLOAD_MS = 3 * 60_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var instance: GemmaOnDevice? = null

    /** One engine per process: two loaded copies would exhaust memory. */
    fun get(ctx: Context, modelFile: File): GemmaOnDevice = synchronized(this) {
      instance?.takeIf { it.modelFile == modelFile }
        ?: GemmaOnDevice(ctx.applicationContext, modelFile).also { instance = it }
    }

    /** Pulls the JSON object out of the reply even if the model wraps it in prose or a code fence. */
    internal fun parseJson(text: String): JSONObject {
      val start = text.indexOf('{')
      val end = text.lastIndexOf('}')
      if (start < 0 || end <= start) return JSONObject()
      return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrDefault(JSONObject())
    }
  }
}
