/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.tools

import kotlinx.coroutines.CompletableDeferred

/** Opens Android Accessibility settings; the user must enable Gallery manually. */
class OpenAccessibilitySettingsToolAction(
  val result: CompletableDeferred<Boolean> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.OPEN_ACCESSIBILITY_SETTINGS)
