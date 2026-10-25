package com.slothassist.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The model server couldn't be used at all (as opposed to giving a bad answer). */
class ModelUnavailable(message: String) : Exception(message)

/** Minimal blocking client for Ollama's /api/chat with JSON-schema constrained output. */
class OllamaClient(private val config: AgentConfig) {

  @Volatile private var active: HttpURLConnection? = null

  /** Cancels an in-flight request from another thread (FR-070). */
  fun abort() {
    active?.disconnect()
  }

  fun chat(system: String, user: String, schema: JSONObject): JSONObject {
    val body = JSONObject()
      .put("model", config.model)
      .put("stream", false)
      .put("think", false)
      .put("keep_alive", "30m")
      .put("format", schema)
      .put("options", JSONObject().put("temperature", 0).put("num_ctx", 8192))
      .put("messages", JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user)))
    val response = request("POST", "/api/chat", body.toString(), readTimeoutMs = 120_000)
    val content = JSONObject(response).getJSONObject("message").getString("content")
    return JSONObject(content)
  }

  /** Names of installed models; also serves as a connectivity check. */
  fun listModels(): List<String> {
    val tags = JSONObject(request("GET", "/api/tags", null, readTimeoutMs = 5_000)).getJSONArray("models")
    return (0 until tags.length()).map { tags.getJSONObject(it).getString("name") }
  }

  private fun request(method: String, path: String, body: String?, readTimeoutMs: Int): String {
    val conn = try {
      URL(config.serverUrl + path).openConnection() as HttpURLConnection
    } catch (e: Exception) {
      throw ModelUnavailable("\"${config.serverUrl}\" isn't a valid server address.")
    }
    active = conn
    try {
      conn.requestMethod = method
      conn.connectTimeout = 4_000
      conn.readTimeout = readTimeoutMs
      if (body != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
      }
      val code = conn.responseCode
      val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
      if (code == 404 && text.contains("not found")) {
        throw ModelUnavailable("The model \"${config.model}\" isn't installed. Run: ollama pull ${config.model}")
      }
      if (code !in 200..299) throw ModelUnavailable("The model server returned $code: ${text.take(200)}")
      return text
    } catch (e: IOException) {
      throw ModelUnavailable(
        "Can't reach the local model at ${config.serverUrl}. Is Ollama running, and is the phone connected " +
          "(adb reverse tcp:11434 tcp:11434)?"
      )
    } finally {
      active = null
      conn.disconnect()
    }
  }
}
