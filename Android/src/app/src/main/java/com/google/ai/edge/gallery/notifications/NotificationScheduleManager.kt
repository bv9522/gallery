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

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.google.ai.edge.gallery.proto.ScheduledNotifications
import com.google.protobuf.InvalidProtocolBufferException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns Gallery's persisted scheduled-notification records and their AlarmManager registrations.
 *
 * All reads and writes go through DataStore. The mutex serializes record changes with alarm
 * replacement/cancellation so concurrent model operations cannot overwrite one another.
 */
@Singleton
class NotificationScheduleManager
@Inject
constructor(@ApplicationContext private val context: Context) {
  private val dataStore: DataStore<ScheduledNotifications> =
    DataStoreFactory.create(
      serializer = ScheduledNotificationsSerializer,
      produceFile = { File(context.filesDir, "scheduled_notifications.pb") },
    )
  private val tag = "NotificationScheduleManager"
  private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutationMutex = Mutex()

  private val _scheduledNotifications = MutableStateFlow<List<ScheduledNotification>>(emptyList())
  val scheduledNotifications = _scheduledNotifications.asStateFlow()

  init {
    // Preserve the existing Notifications screen's startup population while serializing with
    // mutations so a late load can never overwrite a newly scheduled reminder.
    coroutineScope.launch {
      mutationMutex.withLock {
        try {
          _scheduledNotifications.value = dataStore.data.first().notificationList
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.e(tag, "Failed to load scheduled notifications", error)
        }
      }
    }
  }

  fun initialize() = Unit

  /** Returns the persisted list, waiting for DataStore initialization instead of racing it. */
  suspend fun getScheduledNotifications(): List<ScheduledNotification> = mutationMutex.withLock {
    dataStore.data.first().notificationList.also { _scheduledNotifications.value = it }
  }

  /**
   * Persists and schedules a notification. A failed alarm registration rolls the stored record
   * back, so a success result always means both the record and its alarm were accepted.
   */
  suspend fun scheduleNotification(notification: ScheduledNotification): Boolean =
    mutationMutex.withLock {
      val previous = dataStore.data.first()
      val existing = previous.notificationList.firstOrNull { it.id == notification.id }
      val updated = previous.toBuilder()
        .clearNotification()
        .addAllNotification(previous.notificationList.filterNot { it.id == notification.id })
        .addNotification(notification)
        .build()

      try {
        dataStore.updateData { updated }
        _scheduledNotifications.value = updated.notificationList
        existing?.let(::cancelAlarmForNotification)
        setAlarmForNotification(notification)
        true
      } catch (cancelled: CancellationException) {
        restoreSchedule(previous, existing, notification)
        throw cancelled
      } catch (error: Exception) {
        Log.e(tag, "Failed to schedule notification; restoring persisted state", error)
        restoreSchedule(previous, existing, notification)
        false
      }
    }

  private suspend fun restoreSchedule(
    previous: ScheduledNotifications,
    previousNotification: ScheduledNotification?,
    attemptedNotification: ScheduledNotification,
  ) = withContext(NonCancellable) {
    runCatching { cancelAlarmForNotification(attemptedNotification) }
    dataStore.updateData { previous }
    _scheduledNotifications.value = previous.notificationList
    previousNotification?.let { old -> runCatching { setAlarmForNotification(old) } }
  }

  /**
   * Sets a one-shot or daily alarm for a validated notification.
   *
   * All alarms use setAndAllowWhileIdle rather than exact-alarm APIs, avoiding the special
   * exact-alarm access requirement. Daily reminders are re-armed after delivery using local time.
   */
  private fun setAlarmForNotification(notification: ScheduledNotification) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
      ?: throw IllegalStateException("AlarmManager is unavailable.")
    val pendingIntent = buildPendingIntent(notification)
    val calendar = Calendar.getInstance().apply {
      timeInMillis = System.currentTimeMillis()
      if (notification.hasYear() && notification.hasMonth() && notification.hasDay()) {
        set(Calendar.YEAR, notification.year)
        set(Calendar.MONTH, notification.month - 1)
        set(Calendar.DAY_OF_MONTH, notification.day)
      }
      set(Calendar.HOUR_OF_DAY, notification.hour)
      set(Calendar.MINUTE, notification.minute)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
      if (before(Calendar.getInstance())) {
        if (
          notification.repeatDaily ||
            (!notification.hasYear() && !notification.hasMonth() && !notification.hasDay())
        ) {
          add(Calendar.DATE, 1)
        }
      }
    }

    // Use a one-shot alarm for both cases. Daily reminders are re-armed after delivery
    // against the local wall clock so daylight-saving transitions do not shift their time.
    alarmManager.setAndAllowWhileIdle(
      AlarmManager.RTC_WAKEUP,
      calendar.timeInMillis,
      pendingIntent,
    )
  }

  private fun buildPendingIntent(notification: ScheduledNotification) =
    NotificationPendingIntentHelper.buildNotificationPendingIntent(
      context,
      notification.id,
      notification.title,
      notification.message,
      notification.deeplink,
      notification.repeatDaily,
      notification.hour,
      notification.minute,
      notification.channelId,
      notification.channelName,
    )

  private fun cancelAlarmForNotification(notification: ScheduledNotification) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
      ?: throw IllegalStateException("AlarmManager is unavailable.")
    alarmManager.cancel(buildPendingIntent(notification))
  }

  /** Reschedules the authoritative DataStore contents after boot; never reads the backing file directly. */
  fun rescheduleAllNotifications() {
    coroutineScope.launch {
      mutationMutex.withLock {
        try {
          val notifications = dataStore.data.first().notificationList
          _scheduledNotifications.value = notifications
          notifications.forEach { notification ->
            runCatching { setAlarmForNotification(notification) }
              .onFailure { error -> Log.e(tag, "Failed to restore notification ${notification.id}", error) }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.e(tag, "Failed to reschedule notifications", error)
        }
      }
    }
  }

  /**
   * Re-arms a daily reminder for its next local-clock occurrence after the current alarm fires.
   * Recomputing from Calendar each day keeps the reminder at the requested wall-clock time across
   * daylight-saving transitions instead of drifting with a fixed 24-hour interval.
   */
  suspend fun rescheduleDailyNotification(id: String) = mutationMutex.withLock {
    try {
      val notification = dataStore.data.first().notificationList.firstOrNull { it.id == id }
        ?: return@withLock
      if (!notification.repeatDaily) return@withLock
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        ?: throw IllegalStateException("AlarmManager is unavailable.")
      val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, notification.hour)
        set(Calendar.MINUTE, notification.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (!after(Calendar.getInstance())) add(Calendar.DATE, 1)
      }
      alarmManager.setAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        calendar.timeInMillis,
        buildPendingIntent(notification),
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      Log.e(tag, "Failed to re-arm daily notification $id", error)
    }
  }

  /** Cancels a scheduled reminder and removes its persisted record. */
  suspend fun cancelScheduledNotification(id: String): Boolean = mutationMutex.withLock {
    val previous = dataStore.data.first()
    val notification = previous.notificationList.firstOrNull { it.id == id } ?: return@withLock false
    val updated = previous.toBuilder()
      .clearNotification()
      .addAllNotification(previous.notificationList.filterNot { it.id == id })
      .build()
    try {
      dataStore.updateData { updated }
      _scheduledNotifications.value = updated.notificationList
      cancelAlarmForNotification(notification)
      true
    } catch (cancelled: CancellationException) {
      withContext(NonCancellable) {
        dataStore.updateData { previous }
        _scheduledNotifications.value = previous.notificationList
        runCatching { setAlarmForNotification(notification) }
      }
      throw cancelled
    } catch (error: Exception) {
      dataStore.updateData { previous }
      _scheduledNotifications.value = previous.notificationList
      runCatching { setAlarmForNotification(notification) }
      throw error
    }
  }

  /** Compatibility entry point used by the existing BroadcastReceiver after a one-shot fires. */
  fun removeNotification(id: String) {
    coroutineScope.launch {
      try {
        cancelScheduledNotification(id)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Log.e(tag, "Failed to remove completed notification $id", error)
      }
    }
  }
}

object ScheduledNotificationsSerializer : Serializer<ScheduledNotifications> {
  override val defaultValue: ScheduledNotifications = ScheduledNotifications.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): ScheduledNotifications {
    try {
      return ScheduledNotifications.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: ScheduledNotifications, output: OutputStream) = t.writeTo(output)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface NotificationScheduleManagerEntryPoint {
  fun notificationScheduleManager(): NotificationScheduleManager
}
