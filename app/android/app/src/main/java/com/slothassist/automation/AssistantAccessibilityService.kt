package com.slothassist.automation

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * The service Android binds once the user enables it in Accessibility settings.
 * It holds no logic of its own; [ActionExecutor] drives it while a task runs.
 */
class AssistantAccessibilityService : AccessibilityService() {

  override fun onServiceConnected() {
    instance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() {
    TaskRunner.cancel()
  }

  override fun onDestroy() {
    TaskRunner.cancel() // FR-112: stop when the permission goes away
    instance = null
    super.onDestroy()
  }

  companion object {
    @Volatile var instance: AssistantAccessibilityService? = null
      private set

    fun isEnabled(ctx: Context): Boolean {
      val me = ComponentName(ctx, AssistantAccessibilityService::class.java)
      val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
      return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
  }
}
