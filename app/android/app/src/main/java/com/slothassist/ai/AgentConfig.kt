package com.slothassist.ai

import android.content.Context

/** Where the local model lives. Defaults assume Ollama on the laptop, reached over USB via `adb reverse`. */
data class AgentConfig(val serverUrl: String, val model: String) {

  companion object {
    private const val PREFS = "agent"
    const val DEFAULT_URL = "http://127.0.0.1:11434"
    const val DEFAULT_MODEL = "qwen3.5:4b"

    fun load(ctx: Context): AgentConfig {
      val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      return AgentConfig(
        p.getString("url", null) ?: DEFAULT_URL,
        p.getString("model", null) ?: DEFAULT_MODEL,
      )
    }

    fun save(ctx: Context, config: AgentConfig) {
      ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("url", config.serverUrl.trim().trimEnd('/'))
        .putString("model", config.model.trim())
        .apply()
    }
  }
}
