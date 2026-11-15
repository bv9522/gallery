---
name: schedule-notification
description: Schedule a notification for a specific local date and time or repeat it daily.
---

# Schedule Notification

## Instructions

1. Resolve the user's intended local date and time. If the request uses a relative date or an ambiguous time, first call `run_intent` with intent `get_current_date_and_time` and parameters `{}` to obtain the device's local date and time.
2. Calculate the exact local target date and time. Check month/year rollover and do not silently guess an ambiguous time. For a dated reminder, provide all three date fields: `year`, `month`, and `day`.
3. Call `android_control` with operation `schedule_notification`. Supply:
   - `title`: concise notification title (1–100 characters).
   - `message`: notification body (1–1000 characters).
   - `hour`: local 24-hour clock hour (0–23).
   - `minute`: local minute (0–59).
   - `year`, `month`, `day`: all three for a specific date; omit all three for the next occurrence of that local time.
   - `repeat_daily`: true only when the user requested a daily repeating reminder; otherwise false or omit.
4. The native capability requests explicit one-time approval and, when needed, Android notification permission. Do not claim success unless the tool returns success and a notification ID. The app uses inexact alarms to avoid special exact-alarm access; Android may delay delivery because of Doze or battery optimization.
5. Report the exact scheduled local date/time and the returned notification ID. If approval or permission is denied, report that scheduling did not occur.

Use `android_control` operation `list_scheduled_notifications` to review reminders. Use `cancel_scheduled_notification` with the exact returned `notification_id` to cancel one after explicit user approval.

Do not use the legacy `run_intent` schedule_notification action. Do not invent a deeplink, channel ID, or permission state. Reading notifications posted by other apps is not supported by this skill.
