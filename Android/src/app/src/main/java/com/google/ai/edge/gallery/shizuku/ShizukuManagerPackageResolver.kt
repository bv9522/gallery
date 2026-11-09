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

package com.google.ai.edge.gallery.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Resolves the installed Shizuku manager package without assuming the stock application ID. */
object ShizukuManagerPackageResolver {
  const val REQUEST_BINDER_ACTION = "rikka.shizuku.intent.action.REQUEST_BINDER"

  fun resolve(context: Context): String {
    val packageManager = context.packageManager
    val request = Intent(REQUEST_BINDER_ACTION)
    val candidates = buildSet {
      queryBroadcastReceivers(packageManager, request).forEach { info ->
        info.activityInfo?.packageName?.let(::add)
      }
      queryActivities(packageManager, request).forEach { info ->
        info.activityInfo?.packageName?.let(::add)
      }
    }.filterNot { it == context.packageName }

    return selectManagerPackage(candidates)
  }

  internal fun selectManagerPackage(candidates: Collection<String>): String {
    val unique = candidates.filter { it.isNotBlank() }.distinct().sorted()
    return when (unique.size) {
      0 -> throw IllegalStateException(
        "No installed Shizuku manager exposes $REQUEST_BINDER_ACTION. " +
          "Start Shizuku and verify that its manager app is installed."
      )
      1 -> unique.single()
      else -> throw IllegalStateException(
        "Multiple Shizuku manager packages expose $REQUEST_BINDER_ACTION: " +
          unique.joinToString() + ". Keep one manager installed or configure a single manager."
      )
    }
  }

  @Suppress("DEPRECATION")
  private fun queryBroadcastReceivers(packageManager: PackageManager, intent: Intent) =
    if (Build.VERSION.SDK_INT >= 33) {
      packageManager.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
      packageManager.queryBroadcastReceivers(intent, 0)
    }

  @Suppress("DEPRECATION")
  private fun queryActivities(packageManager: PackageManager, intent: Intent) =
    if (Build.VERSION.SDK_INT >= 33) {
      packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
      packageManager.queryIntentActivities(intent, 0)
    }
}
