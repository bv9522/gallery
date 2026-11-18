/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Narrow opt-in lifecycle/status surface; no screen content is exposed by this first slice. */
class AccessibilityControlCapabilityTool internal constructor(
  private val access: AccessibilityServiceAccess,
  private val authorizer: NativeCapabilityAuthorizer,
) : AgentTool {
  constructor(context: Context, actionChannel: kotlinx.coroutines.channels.SendChannel<com.google.ai.edge.gallery.tools.ToolAction>) : this(
    AndroidAccessibilityServiceAccess(context.applicationContext),
    ChannelNativeCapabilityAuthorizer(actionChannel),
  )

  override val name = "accessibility_control"
  override val description =
    "Use operation=status to check whether the user has manually enabled Gallery's AccessibilityService. Use operation=open_settings only when the user explicitly asks to enable or disable it; the user must manually toggle Gallery in Android Accessibility settings. Android grants this service broad ability to observe and interact with the screen, so explain that scope before opening settings. This initial lifecycle operation does not inspect screen content, read notifications, capture screenshots, or perform UI actions. Never claim the service was enabled or disabled by opening settings."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { add("status"); add("open_settings") })
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val operation = arguments.get("operation")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
      ?: return AgentToolResult("operation must be status or open_settings.", false)
    if (arguments.keySet() != setOf("operation")) {
      return AgentToolResult("Unexpected arguments. Only operation is accepted.", false)
    }
    return when (operation) {
      "status" -> if (access.isEnabled()) {
        AgentToolResult("Gallery's AccessibilityService is enabled by the user. This lifecycle-only slice does not inspect or interact with screen content.")
      } else {
        AgentToolResult("Gallery's AccessibilityService is disabled. No screen content is accessed. The user can enable it manually in Android Accessibility settings.")
      }
      "open_settings" -> {
        if (!authorizer.authorize(
            AndroidCapability.UI_TREE_READ,
            "Open Android Accessibility settings so the user can manually enable or disable Gallery's screen-access service. Android describes this as broad screen observation and interaction access; opening settings does not grant access.",
          )) {
          AgentToolResult("Opening Android Accessibility settings was not approved.", false)
        } else if (access.openSettings()) {
          AgentToolResult("Android Accessibility settings opened. Gallery remains disabled or enabled according to the user's manual choice; no access was granted automatically.")
        } else {
          AgentToolResult("Android Accessibility settings could not be opened.", false)
        }
      }
      else -> AgentToolResult("Unsupported operation. Choose status or open_settings.", false)
    }
  }
}

internal interface AccessibilityServiceAccess {
  fun isEnabled(): Boolean
  fun openSettings(): Boolean
}

private class AndroidAccessibilityServiceAccess(
  private val context: Context,
) : AccessibilityServiceAccess {
  override fun isEnabled(): Boolean {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
      ?: return false
    val expectedId = ComponentName(context, GalleryAccessibilityService::class.java).flattenToString()
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      .any { it.id == expectedId }
  }

  override fun openSettings(): Boolean = try {
    context.startActivity(
      Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
  } catch (_: ActivityNotFoundException) {
    false
  } catch (_: SecurityException) {
    false
  }
}
