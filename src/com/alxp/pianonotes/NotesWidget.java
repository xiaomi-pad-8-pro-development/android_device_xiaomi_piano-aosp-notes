package com.alxp.pianonotes;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** Home screen widget with recent notes, like Xiaomi Notes'; {@link Tasks} lists tasks. */
public class NotesWidget extends AppWidgetProvider {
    static final String EXTRA_TASKS = "com.alxp.pianonotes.WIDGET_TASKS";
    static final String EXTRA_NEW = "com.alxp.pianonotes.NEW";
    static final String EXTRA_TASK = "com.alxp.pianonotes.TASK";
    private static final String ACTION_TOGGLE_TASK = "com.alxp.pianonotes.TOGGLE_TASK";

    /** The Tasks widget: tap a task to tick it off. */
    public static final class Tasks extends NotesWidget {
        @Override boolean tasks() {
            return true;
        }
    }

    boolean tasks() {
        return false;
    }

    /** Redraws both widgets after notes or tasks change. */
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (Class<?> type : new Class<?>[]{NotesWidget.class, Tasks.class}) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, type));
            if (ids.length > 0) manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_items);
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_list);
            views.setTextViewText(R.id.widget_title, tasks() ? "Tasks" : "Notes");
            views.setTextViewText(R.id.widget_empty, tasks() ? "No tasks" : "No notes");
            Intent service = new Intent(context, Service.class)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(EXTRA_TASKS, tasks());
            service.setData(Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.widget_items, service);
            views.setEmptyView(R.id.widget_items, R.id.widget_empty);

            Intent add = new Intent(context, NotesActivity.class)
                    .putExtra(EXTRA_NEW, true)
                    .putExtra(EXTRA_TASKS, tasks())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            views.setOnClickPendingIntent(R.id.widget_add, PendingIntent.getActivity(context,
                    tasks() ? 2 : 1, add,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context,
                    tasks() ? 4 : 3, new Intent(context, NotesActivity.class)
                            .putExtra(EXTRA_TASKS, tasks())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

            // Items fill in the note or task; the template opens the note or ticks the task.
            Intent template = tasks()
                    ? new Intent(context, Tasks.class).setAction(ACTION_TOGGLE_TASK)
                    : new Intent(context, NotesActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            views.setPendingIntentTemplate(R.id.widget_items, tasks()
                    ? PendingIntent.getBroadcast(context, 5, template,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE)
                    : PendingIntent.getActivity(context, 6, template,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
            manager.updateAppWidget(id, views);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION_TOGGLE_TASK.equals(intent.getAction())) {
            long task = intent.getLongExtra(EXTRA_TASK, -1);
            try (NoteStore store = new NoteStore(context)) {
                for (NoteStore.Task item : store.listTasks()) {
                    if (item.id == task) store.setTaskDone(task, !item.done);
                }
            }
            refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    /** Supplies the widget rows. */
    public static final class Service extends RemoteViewsService {
        @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
            return new Rows(getApplicationContext(), intent.getBooleanExtra(EXTRA_TASKS, false));
        }
    }

    private static final class Rows implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private final boolean tasks;
        private final List<String[]> rows = new ArrayList<>();
        private final List<Long> ids = new ArrayList<>();

        Rows(Context context, boolean tasks) {
            this.context = context;
            this.tasks = tasks;
        }

        @Override public void onCreate() {}

        @Override public void onDataSetChanged() {
            rows.clear();
            ids.clear();
            try (NoteStore store = new NoteStore(context)) {
                if (tasks) {
                    for (NoteStore.Task task : store.listTasks()) {
                        rows.add(new String[]{(task.done ? "☑  " : "☐  ") + task.text, ""});
                        ids.add(task.id);
                    }
                } else {
                    for (NoteStore.NoteSummary note : store.listSummaries()) {
                        if (note.deleted || note.hidden) continue;
                        String title = note.title.isEmpty() ? firstLine(note.preview) : note.title;
                        rows.add(new String[]{title, note.title.isEmpty() ? "" : note.preview});
                        ids.add(note.id);
                        if (rows.size() == 30) break;
                    }
                }
            }
        }

        private static String firstLine(String text) {
            int newline = text.indexOf('\n');
            return newline < 0 ? text : text.substring(0, newline);
        }

        @Override public void onDestroy() {}

        @Override public int getCount() {
            return rows.size();
        }

        @Override public RemoteViews getViewAt(int position) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_item);
            row.setTextViewText(R.id.widget_item_title, rows.get(position)[0]);
            row.setTextViewText(R.id.widget_item_text, rows.get(position)[1]);
            row.setViewVisibility(R.id.widget_item_text,
                    rows.get(position)[1].isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            Intent fill = new Intent().putExtra(tasks ? EXTRA_TASK : Reminders.EXTRA_NOTE,
                    ids.get(position));
            row.setOnClickFillInIntent(R.id.widget_item, fill);
            return row;
        }

        @Override public RemoteViews getLoadingView() {
            return null;
        }

        @Override public int getViewTypeCount() {
            return 1;
        }

        @Override public long getItemId(int position) {
            return ids.get(position);
        }

        @Override public boolean hasStableIds() {
            return true;
        }
    }
}
