/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives one-shot result callbacks from Termux RunCommandService.
 *
 * The receiver is private to this application. Pending result entries are process-local and are
 * removed by TermuxRunCommandClient after completion, timeout, or cancellation.
 */
class TermuxCommandResultReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val requestId = intent.getIntExtra(EXTRA_REQUEST_ID, INVALID_REQUEST_ID)
    if (requestId == INVALID_REQUEST_ID) return

    val result = intent.getBundleExtra(TermuxRunCommandClient.EXTRA_PLUGIN_RESULT_BUNDLE)
      ?: Bundle().apply {
        putInt(TermuxRunCommandClient.EXTRA_PLUGIN_RESULT_BUNDLE_ERR, ERROR_MISSING_RESULT)
        putString(
          TermuxRunCommandClient.EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG,
          "Termux returned no result bundle.",
        )
      }
    pendingResults.remove(requestId)?.complete(result)
  }

  companion object {
    const val EXTRA_REQUEST_ID = "com.google.ai.edge.gallery.termux.REQUEST_ID"
    private const val INVALID_REQUEST_ID = -1
    private const val ERROR_MISSING_RESULT = -1001
    private val nextRequestId = AtomicInteger(1_000)
    private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()

    internal fun register(): Pair<Int, CompletableDeferred<Bundle>> {
      val id = nextRequestId.getAndIncrement()
      val result = CompletableDeferred<Bundle>()
      check(pendingResults.putIfAbsent(id, result) == null) {
        "Termux request identifier collision."
      }
      return id to result
    }

    internal fun remove(requestId: Int) {
      pendingResults.remove(requestId)?.cancel()
    }
  }
}
