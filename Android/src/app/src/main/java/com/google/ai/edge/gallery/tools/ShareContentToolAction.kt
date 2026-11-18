/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.tools

import kotlinx.coroutines.CompletableDeferred

/** Opens Android's system share chooser; completion means only that the chooser was launched. */
class ShareContentToolAction(
  val mimeType: String,
  val text: String? = null,
  val contentUri: String? = null,
  val displayName: String? = null,
  val chooserTitle: String,
  val result: CompletableDeferred<Boolean> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.SHARE_CONTENT)
