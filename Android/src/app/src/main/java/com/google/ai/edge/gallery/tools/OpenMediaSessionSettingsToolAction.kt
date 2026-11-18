/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.tools

import kotlinx.coroutines.CompletableDeferred

/** Opens notification-listener settings; the user must enable Gallery manually. */
class OpenMediaSessionSettingsToolAction(
  val result: CompletableDeferred<Boolean> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.OPEN_MEDIA_SESSION_SETTINGS)
