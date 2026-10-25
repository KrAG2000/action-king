package com.slothassist.ai

import org.json.JSONObject

/**
 * A local model that answers one prompt with JSON matching [schema].
 * [compactRegex], when given, is a stricter shape for small on-device models: no whitespace and
 * no free-form fields, so they can't ramble or pad the reply until they run out of tokens.
 */
interface ChatModel {
  fun chat(system: String, user: String, schema: JSONObject, compactRegex: String? = null): JSONObject

  /** Cancels an in-flight [chat] from another thread (FR-070). */
  fun abort()
}
