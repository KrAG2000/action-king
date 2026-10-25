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
import com.slothassist.ai.ModelUnavailable
import com.slothassist.ai.OllamaClient
import com.slothassist.automation.AssistantAccessibilityService
import com.slothassist.overlay.FloatingOverlayService

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
      putString("serverUrl", c.serverUrl)
      putString("model", c.model)
    })
  }

  @ReactMethod
  fun setAgentConfig(serverUrl: String, model: String) {
    AgentConfig.save(ctx, AgentConfig(serverUrl, model))
  }

  /** Checks the server is reachable and the chosen model is installed. Resolves with installed model names. */
  @ReactMethod
  fun testModel(promise: Promise) {
    Thread {
      val config = AgentConfig.load(ctx)
      try {
        val models = OllamaClient(config).listModels()
        val installed = models.any { it == config.model || it == "${config.model}:latest" }
        promise.resolve(Arguments.createMap().apply {
          putBoolean("reachable", true)
          putBoolean("modelInstalled", installed)
          putArray("models", Arguments.fromList(models))
        })
      } catch (e: ModelUnavailable) {
        promise.resolve(Arguments.createMap().apply {
          putBoolean("reachable", false)
          putBoolean("modelInstalled", false)
          putString("error", e.message)
        })
      }
    }.start()
  }

  private fun launch(intent: Intent) {
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  companion object {
    const val NAME = "AssistantBridge"
  }
}
