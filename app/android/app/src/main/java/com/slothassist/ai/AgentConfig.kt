package com.slothassist.ai

import android.content.Context
import java.io.File

/**
 * Which local model plans the steps.
 * - [Provider.ON_DEVICE]: Gemma running inside this app with LiteRT-LM (default when the model file is present).
 * - [Provider.OLLAMA]: Ollama on the user's computer, reached over USB (`adb reverse`) or the LAN.
 */
data class AgentConfig(
  val provider: Provider,
  val modelPath: String,
  val serverUrl: String,
  val model: String,
) {

  enum class Provider { ON_DEVICE, OLLAMA }

  fun createModel(ctx: Context): ChatModel = when (provider) {
    Provider.ON_DEVICE -> GemmaOnDevice.get(ctx, File(modelPath))
    Provider.OLLAMA -> OllamaClient(this)
  }

  companion object {
    private const val PREFS = "agent"
    const val DEFAULT_URL = "http://127.0.0.1:11434"
    const val DEFAULT_MODEL = "qwen3.5:4b"
    const val GEMMA_FILE = "gemma-4-E2B-it.litertlm"

    /** App-specific storage: readable by us without any permission, and writable by `adb shell`. */
    fun defaultModelFile(ctx: Context): File =
      File(ctx.getExternalFilesDir(null), "models/$GEMMA_FILE").also { it.parentFile?.mkdirs() }

    fun load(ctx: Context): AgentConfig {
      val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      val modelFile = defaultModelFile(ctx)
      val provider = p.getString("provider", null)?.let(Provider::valueOf)
        ?: if (modelFile.exists()) Provider.ON_DEVICE else Provider.OLLAMA
      return AgentConfig(
        provider,
        modelFile.absolutePath,
        p.getString("url", null) ?: DEFAULT_URL,
        p.getString("model", null) ?: DEFAULT_MODEL,
      )
    }

    fun save(ctx: Context, config: AgentConfig) {
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("provider", config.provider.name)
        .putString("url", config.serverUrl.trim().trimEnd('/'))
        .putString("model", config.model.trim())
        .apply()
    }
  }
}
