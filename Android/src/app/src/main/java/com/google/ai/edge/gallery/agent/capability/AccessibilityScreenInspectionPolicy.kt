/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

/** Pure privacy policy shared by the Android adapter and unit tests. */
internal object AccessibilityScreenInspectionPolicy {
  const val MAX_LABEL_CHARS = 120
  const val MIN_WINDOW_SCREENSHOT_API = 34

  fun supportsWindowScreenshot(apiLevel: Int): Boolean =
    apiLevel >= MIN_WINDOW_SCREENSHOT_API
  private val sensitiveWords = Regex(
    """\b(sign[ -]?in|sign[ -]?up|log[ -]?in|password|passcode|pin|one[ -]?time code|verification code|otp|2fa|mfa|account|profile|personal information|beneficiary|routing number|iban|sort code|crypto|cvv|cvc|credit card|debit card|card number|bank|payment|checkout|transfer|wallet|billing|account number|social security|ssn|recovery phrase|seed phrase|private key|security code|tax return|medical record|patient portal|prescription|diagnosis|email address|phone number|date of birth|passport|driver.?s license|government id|address)\b""",
    RegexOption.IGNORE_CASE,
  )
  private val emailPattern = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
  private val longNumberPattern = Regex("""(?<!\w)\d{6,}(?!\w)""")
  private val phonePattern = Regex("""(?<!\w)\+?\d(?:[\d\s().-]{7,}\d)(?!\w)""")
  private val protectedPackageFragments = listOf(
    "bank", "wallet", "payment", "pay.", ".pay", "finance", "broker", "trading",
    "password", "credential", "authenticator", "identity", "vault", "health", "paypal", "venmo", "cashapp", "crypto", "chase", "capitalone", "wellsfargo", "revolut", "wise",
    "medical", "patient", "insurance", "tax", "government",
  )
  private val systemPackages = setOf(
    "com.android.settings",
    "com.android.systemui",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
  )

  fun isSensitivePackage(packageName: String): Boolean {
    val normalized = packageName.lowercase()
    return normalized in systemPackages || protectedPackageFragments.any { normalized.contains(it) }
  }

  fun containsSensitiveText(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return sensitiveWords.containsMatchIn(value) ||
      emailPattern.containsMatchIn(value) ||
      longNumberPattern.containsMatchIn(value) ||
      phonePattern.containsMatchIn(value)
  }

  fun sanitizeLabel(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val sanitized = buildString {
      value.forEach { character ->
        val category = Character.getType(character)
        append(if (character.isISOControl() || category == Character.FORMAT.toInt()) ' ' else character)
      }
    }.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL_CHARS)
    return sanitized.takeIf { it.isNotEmpty() }
  }

  fun role(className: String): String {
    val name = className.substringAfterLast('.').lowercase()
    return when {
      name.contains("button") -> "button"
      name.contains("edittext") || name.contains("textfield") -> "text_field"
      name.contains("image") -> "image"
      name.contains("checkbox") -> "checkbox"
      name.contains("switch") -> "switch"
      name.contains("radiobutton") -> "radio"
      name.contains("seekbar") -> "slider"
      name.contains("recyclerview") || name.contains("listview") -> "list"
      name.contains("scrollview") -> "scroll_container"
      name.contains("textview") -> "text"
      else -> "element"
    }
  }

  fun isValidHandle(value: String): Boolean = value.matches(Regex("ui_[a-f0-9]{16}"))
}
