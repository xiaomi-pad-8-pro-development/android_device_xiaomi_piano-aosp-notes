package com.alxp.pianonotes;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Note reminders, as in Xiaomi Notes: an alarm at the chosen time posts a
 * notification that opens the note. Alarms are set again after a reboot.
 */
public final class Reminders extends BroadcastReceiver {
    static final String EXTRA_NOTE = "com.alxp.pianonotes.NOTE";
    private static final String ACTION_REMIND = "com.alxp.pianonotes.REMIND";
    private static final String CHANNEL = "reminders";

    static void schedule(Context context, long noteId, long when) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent alarm = alarmIntent(context, noteId);
        alarms.cancel(alarm);
        if (when <= System.currentTimeMillis()) return;
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, alarm);
        } else {
            // Without the exact alarm permission the reminder may come a few minutes late.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, alarm);
        }
    }

    static void cancel(Context context, long noteId) {
        context.getSystemService(AlarmManager.class).cancel(alarmIntent(context, noteId));
    }

    private static PendingIntent alarmIntent(Context context, long noteId) {
        Intent intent = new Intent(context, Reminders.class)
                .setAction(ACTION_REMIND)
                .putExtra(EXTRA_NOTE, noteId);
        return PendingIntent.getBroadcast(context, (int) noteId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openNote(Context context, long noteId) {
        Intent intent = new Intent(context, NotesActivity.class)
                .putExtra(EXTRA_NOTE, noteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, (int) noteId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)) {
            try (NoteStore store = new NoteStore(context)) {
                for (long[] reminder : store.pendingReminders()) {
                    schedule(context, reminder[0], reminder[1]);
                }
            }
            return;
        }
        if (!ACTION_REMIND.equals(action)) return;
        long noteId = intent.getLongExtra(EXTRA_NOTE, -1);
        String title;
        String text;
        try (NoteStore store = new NoteStore(context)) {
            NoteStore.Note note = store.get(noteId);
            if (note == null) return;
            store.setReminder(noteId, 0);
            title = note.title.isEmpty() ? "Note reminder" : note.title;
            text = note.ink ? "Handwritten note" : note.body;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Reminders",
                NotificationManager.IMPORTANCE_HIGH));
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_note_add_24)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openNote(context, noteId))
                .setAutoCancel(true)
                .build();
        manager.notify((int) noteId, notification);
    }
}
