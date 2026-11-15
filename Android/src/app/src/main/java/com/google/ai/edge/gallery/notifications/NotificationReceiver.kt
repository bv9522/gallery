/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationReceiver : BroadcastReceiver() {
  private val DEFAULT_CHANNEL_ID = "ai_edge_gallery_notification_channel"
  private val DEFAULT_CHANNEL_NAME = "AI Edge Gallery Notifications"

  override fun onReceive(context: Context, intent: Intent) {
    Log.d(TAG, "onReceive called with intent: $intent")
    val id = intent.getStringExtra(NotificationPendingIntentHelper.EXTRA_ID) ?: ""
    val title =
      intent.getStringExtra(NotificationPendingIntentHelper.EXTRA_TITLE) ?: "Scheduled task"
    val message =
      intent.getStringExtra(NotificationPendingIntentHelper.EXTRA_MESSAGE)
        ?: "Time to complete your task!"
    val deeplink = intent.getStringExtra(NotificationPendingIntentHelper.EXTRA_DEEPLINK) ?: ""
    val channelId =
      intent.getStringExtra(NotificationPendingIntentHelper.EXTRA_CHANNEL_ID) ?: DEFAULT_CHANNEL_ID
    val channelName =
      intent.getStringExtra(NotificationPendingIntentHelper.EXTRA_CHANNEL_NAME)
        ?: DEFAULT_CHANNEL_NAME
    val repeatDaily = intent.getBooleanExtra(NotificationPendingIntentHelper.EXTRA_REPEAT_DAILY, false)

    try {
      // Create the intent for when tapping the notification
      val contentIntent =
        Intent(Intent.ACTION_VIEW).apply {
          if (deeplink.isNotEmpty()) {
            data = deeplink.toUri()
          }
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

      val pendingIntent =
        PendingIntent.getActivity(
          context,
          if (id.isNotEmpty()) id.hashCode() else System.currentTimeMillis().toInt(),
          contentIntent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

      val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel =
          NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
      }

      val notificationBuilder =
        NotificationCompat.Builder(context, channelId)
          .setSmallIcon(android.R.drawable.ic_dialog_info)
          .setContentTitle(title)
          .setContentText(message)
          .setAutoCancel(true)
          .setContentIntent(pendingIntent)
          .setPriority(NotificationCompat.PRIORITY_HIGH)

      notificationManager.notify(if (id.isNotEmpty()) id.hashCode() else System.currentTimeMillis().toInt(), notificationBuilder.build())
      updateSchedule(context, id, repeatDaily)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to send notification", e)
      // A failed post must not leave a consumed one-shot as a stale reminder or silently
      // stop a daily reminder. Remove one-shots and re-arm daily reminders at local wall time.
      runCatching { updateSchedule(context, id, repeatDaily) }
    }
  }

  private fun updateSchedule(context: Context, id: String, repeatDaily: Boolean) {
    if (id.isEmpty()) return
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        val manager = entryPoint.notificationScheduleManager()
        if (repeatDaily) manager.rescheduleDailyNotification(id)
        else manager.cancelScheduledNotification(id)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Log.e(TAG, "Failed to update schedule after notification delivery", error)
      } finally {
        pendingResult.finish()
      }
    }
  }

  companion object {
    private const val TAG = "NotificationReceiver"
  }
}
