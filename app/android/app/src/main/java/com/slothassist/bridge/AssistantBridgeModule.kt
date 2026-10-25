package com.slothassist.bridge

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.slothassist.ai.AgentConfig
import com.slothassist.ai.GemmaOnDevice
import com.slothassist.ai.ModelUnavailable
import com.slothassist.ai.OllamaClient
import com.slothassist.automation.AssistantAccessibilityService
import com.slothassist.overlay.FloatingOverlayService
import java.io.File

/** What the React Native screens need from the native side: permission status and the bubble switch. */
class AssistantBridgeModule(private val ctx: ReactApplicationContext) : ReactContextBaseJavaModule(ctx) {

  override fun getName() = NAME

  @ReactMethod
  fun getStatus(promise: Promise) {
    promise.resolve(Arguments.createMap().apply {
      putBoolean("overlay", Settings.canDrawOverlays(ctx))
      putBoolean("accessibility", AssistantAccessibilityService.isEnabled(ctx))
      putBoolean("accessibilityConnected", AssistantAccessibilityService.instance != null)
      putBoolean("bubbleRunning", FloatingOverlayService.running)
    })
  }

  @ReactMethod
  fun openOverlaySettings() {
    launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
  }

  @ReactMethod
  fun openAccessibilitySettings() {
    launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
  }

  @ReactMethod
  fun openAppSettings() {
    launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
  }

  @ReactMethod
  fun startBubble(promise: Promise) {
    if (!Settings.canDrawOverlays(ctx)) {
      promise.reject("NO_OVERLAY", "Allow \"Display over other apps\" first.")
      return
    }
    FloatingOverlayService.start(ctx)
    promise.resolve(true)
  }

  @ReactMethod
  fun stopBubble() {
    FloatingOverlayService.stop(ctx)
  }

  @ReactMethod
  fun getAgentConfig(promise: Promise) {
    val c = AgentConfig.load(ctx)
    promise.resolve(Arguments.createMap().apply {
      putString("provider", c.provider.name)
      putString("modelPath", c.modelPath)
      putBoolean("modelFileExists", File(c.modelPath).exists())
      putString("serverUrl", c.serverUrl)
      putString("model", c.model)
    })
  }

  @ReactMethod
  fun setAgentConfig(provider: String, serverUrl: String, model: String) {
    val current = AgentConfig.load(ctx)
    AgentConfig.save(ctx, current.copy(provider = AgentConfig.Provider.valueOf(provider), serverUrl = serverUrl, model = model))
  }

  /**
   * On-device: loads Gemma and runs a tiny prompt. Ollama: checks the server and that the model is installed.
   * Resolves with { ok, message }.
   */
  @ReactMethod
  fun testModel(promise: Promise) {
    Thread {
      val config = AgentConfig.load(ctx)
      val (ok, message) = try {
        when (config.provider) {
          AgentConfig.Provider.ON_DEVICE -> true to GemmaOnDevice.get(ctx, File(config.modelPath)).warmUp()
          AgentConfig.Provider.OLLAMA -> {
            val models = OllamaClient(config).listModels()
            if (models.any { it == config.model || it == "${config.model}:latest" }) true to "Connected ✓ ${config.model} is ready."
            else false to "Connected, but \"${config.model}\" isn't installed. Available: ${models.joinToString().ifEmpty { "none" }}"
          }
        }
      } catch (e: ModelUnavailable) {
        false to (e.message ?: "The model isn't available.")
      } catch (e: Exception) {
        false to "Model test failed: ${e.message}"
      }
      promise.resolve(Arguments.createMap().apply {
        putBoolean("ok", ok)
        putString("message", message)
      })
    }.start()
  }

  private fun launch(intent: Intent) {
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  companion object {
    const val NAME = "AssistantBridge"
  }
}
