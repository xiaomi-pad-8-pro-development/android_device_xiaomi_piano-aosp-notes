package com.alxp.pianonotes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class NoteStore extends SQLiteOpenHelper {
    private static final String DATABASE = "piano_notes.db";
    private static final int VERSION = 5;
    /** Notes in the trash longer than this are deleted for good. */
    private static final long TRASH_DAYS_MS = 30L * 24 * 60 * 60 * 1000;

    NoteStore(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notes ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "body TEXT NOT NULL DEFAULT '',"
                + "is_ink INTEGER NOT NULL DEFAULT 0,"
                + "ink_json TEXT NOT NULL DEFAULT '',"
                + "updated INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX notes_updated_idx ON notes(updated DESC)");
        addOrganizing(db);
        addRichText(db);
        addTasks(db);
        addExtras(db);
    }

    /** Schema 5: reminders, hidden notes and mind maps. */
    private static void addExtras(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE notes ADD COLUMN remind_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE notes ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE notes ADD COLUMN mindmap INTEGER NOT NULL DEFAULT 0");
    }

    /** Schema 4: the to-do list on the Tasks tab. */
    private static void addTasks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tasks ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "text TEXT NOT NULL,"
                + "done INTEGER NOT NULL DEFAULT 0,"
                + "created INTEGER NOT NULL,"
                + "done_at INTEGER NOT NULL DEFAULT 0)");
    }

    /** Schema 3: character styles of text notes. */
    private static void addRichText(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE notes ADD COLUMN spans TEXT NOT NULL DEFAULT ''");
    }

    /** Schema 2: folders, pinned notes and a trash. */
    private static void addOrganizing(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE folders ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL)");
        db.execSQL("ALTER TABLE notes ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (newVersion == 5 && oldVersion >= 1 && oldVersion <= 4) {
            if (oldVersion < 2) addOrganizing(db);
            if (oldVersion < 3) addRichText(db);
            if (oldVersion < 4) addTasks(db);
            addExtras(db);
            return;
        }
        // Fail closed until an explicit forward migration is added. SQLiteOpenHelper
        // runs this callback transactionally, so throwing preserves the old schema
        // version and prevents the app from silently treating unmigrated notes as current.
        throw new SQLiteException("No notes database migration from schema " + oldVersion
                + " to " + newVersion + ".");
    }

    long save(long id, String title, String body, boolean ink, String inkJson) {
        return save(id, title, body, ink, inkJson, "");
    }

    long save(long id, String title, String body, boolean ink, String inkJson, String spans) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", title == null ? "" : title.trim());
        values.put("body", body == null ? "" : body);
        values.put("is_ink", ink ? 1 : 0);
        values.put("ink_json", inkJson == null ? "" : inkJson);
        values.put("spans", spans == null ? "" : spans);
        values.put("updated", now);
        if (id < 0) return getWritableDatabase().insertOrThrow("notes", null, values);
        int changed = getWritableDatabase().update("notes", values, "_id=?",
                new String[]{Long.toString(id)});
        return changed == 0
                ? getWritableDatabase().insertOrThrow("notes", null, values) : id;
    }

    List<NoteSummary> listSummaries() {
        List<NoteSummary> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT _id, title, CASE WHEN is_ink != 0 THEN 'Ink page' "
                        + "ELSE substr(body, 1, 90) END, is_ink, updated, "
                        + "folder_id, pinned, deleted, remind_at, hidden, mindmap "
                        + "FROM notes ORDER BY pinned DESC, updated DESC, _id DESC", null)) {
            while (cursor.moveToNext()) {
                NoteSummary summary = new NoteSummary(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3) != 0, cursor.getLong(4),
                        cursor.getLong(5), cursor.getInt(6) != 0, cursor.getLong(7) != 0);
                summary.remindAt = cursor.getLong(8);
                summary.hidden = cursor.getInt(9) != 0;
                summary.mindmap = cursor.getInt(10) != 0;
                result.add(summary);
            }
        }
        return result;
    }

    Note get(long id) {
        try (Cursor cursor = getReadableDatabase().query("notes",
                new String[]{"_id", "title", "body", "is_ink", "ink_json", "updated", "spans",
                        "mindmap", "remind_at"},
                "_id=?", new String[]{Long.toString(id)}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            Note note = new Note(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3) != 0, cursor.getString(4), cursor.getLong(5),
                    cursor.getString(6));
            note.mindmap = cursor.getInt(7) != 0;
            note.remindAt = cursor.getLong(8);
            return note;
        }
    }

    void forEachNote(NoteConsumer consumer) throws Exception {
        try (Cursor cursor = getReadableDatabase().query("notes",
                new String[]{"_id", "title", "body", "is_ink", "ink_json", "updated", "spans",
                        "mindmap"},
                null, null, null, null, "updated DESC, _id DESC")) {
            while (cursor.moveToNext()) {
                Note note = new Note(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3) != 0,
                        cursor.getString(4), cursor.getLong(5), cursor.getString(6));
                note.mindmap = cursor.getInt(7) != 0;
                consumer.accept(note);
            }
        }
    }

    void setPinned(long id, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        update(id, values);
    }

    /** Keeps an imported note's original date. */
    void setUpdated(long id, long when) {
        if (when <= 0) return;
        ContentValues values = new ContentValues();
        values.put("updated", when);
        update(id, values);
    }

    void setReminder(long id, long when) {
        ContentValues values = new ContentValues();
        values.put("remind_at", when);
        update(id, values);
    }

    void setHidden(long id, boolean hidden) {
        ContentValues values = new ContentValues();
        values.put("hidden", hidden ? 1 : 0);
        values.put("pinned", 0);
        update(id, values);
    }

    void setMindMap(long id, boolean mindmap) {
        ContentValues values = new ContentValues();
        values.put("mindmap", mindmap ? 1 : 0);
        update(id, values);
    }

    /** Notes with a reminder still to come, for rescheduling after a reboot. */
    List<long[]> pendingReminders() {
        List<long[]> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT _id, remind_at FROM notes WHERE remind_at > ? AND deleted = 0",
                new String[]{Long.toString(System.currentTimeMillis())})) {
            while (cursor.moveToNext()) {
                result.add(new long[]{cursor.getLong(0), cursor.getLong(1)});
            }
        }
        return result;
    }

    void moveToFolder(long id, long folderId) {
        ContentValues values = new ContentValues();
        values.put("folder_id", folderId);
        update(id, values);
    }

    /** Moves a note to the trash; it can be restored for 30 days. */
    void trash(long id) {
        ContentValues values = new ContentValues();
        values.put("deleted", System.currentTimeMillis());
        values.put("pinned", 0);
        update(id, values);
    }

    void restore(long id) {
        ContentValues values = new ContentValues();
        values.put("deleted", 0);
        update(id, values);
    }

    void emptyTrash() {
        getWritableDatabase().delete("notes", "deleted != 0", null);
    }

    /** Deletes notes that have been in the trash for more than 30 days. */
    void purgeTrash() {
        getWritableDatabase().delete("notes", "deleted != 0 AND deleted < ?",
                new String[]{Long.toString(System.currentTimeMillis() - TRASH_DAYS_MS)});
    }

    private void update(long id, ContentValues values) {
        getWritableDatabase().update("notes", values, "_id=?",
                new String[]{Long.toString(id)});
    }

    List<Folder> listFolders() {
        List<Folder> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT _id, name FROM folders ORDER BY name COLLATE NOCASE", null)) {
            while (cursor.moveToNext()) {
                result.add(new Folder(cursor.getLong(0), cursor.getString(1)));
            }
        }
        return result;
    }

    long addFolder(String name) {
        ContentValues values = new ContentValues();
        values.put("name", name.trim());
        return getWritableDatabase().insertOrThrow("folders", null, values);
    }

    void renameFolder(long id, String name) {
        ContentValues values = new ContentValues();
        values.put("name", name.trim());
        getWritableDatabase().update("folders", values, "_id=?",
                new String[]{Long.toString(id)});
    }

    /** Deletes a folder; its notes stay, outside any folder. */
    void deleteFolder(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("folder_id", 0);
            db.update("notes", values, "folder_id=?", new String[]{Long.toString(id)});
            db.delete("folders", "_id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void delete(long id) {
        getWritableDatabase().delete("notes", "_id=?", new String[]{Long.toString(id)});
    }

    void replaceAll(NoteSource source) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("notes", null, null);
            source.read(note -> {
                ContentValues values = new ContentValues();
                values.put("title", note.title);
                values.put("body", note.body);
                values.put("is_ink", note.ink ? 1 : 0);
                values.put("ink_json", note.inkJson);
                values.put("updated", note.updated);
                values.put("spans", note.spans);
                values.put("mindmap", note.mindmap ? 1 : 0);
                db.insertOrThrow("notes", null, values);
            });
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    interface NoteConsumer {
        void accept(Note note) throws Exception;
    }

    interface NoteSource {
        void read(NoteConsumer consumer) throws Exception;
    }

    static final class Note {
        final long id;
        final String title;
        final String body;
        final boolean ink;
        final String inkJson;
        final long updated;
        final String spans;
        boolean mindmap;
        long remindAt;

        Note(long id, String title, String body, boolean ink, String inkJson, long updated) {
            this(id, title, body, ink, inkJson, updated, "");
        }

        Note(long id, String title, String body, boolean ink, String inkJson, long updated,
                String spans) {
            this.spans = spans == null ? "" : spans;
            this.id = id;
            this.title = title;
            this.body = body;
            this.ink = ink;
            this.inkJson = inkJson;
            this.updated = updated;
        }
    }

    static final class NoteSummary {
        final long id;
        final String title;
        final String preview;
        final boolean ink;
        final long updated;

        final long folderId;
        final boolean pinned;
        final boolean deleted;
        long remindAt;
        boolean hidden;
        boolean mindmap;

        NoteSummary(long id, String title, String preview, boolean ink, long updated,
                long folderId, boolean pinned, boolean deleted) {
            this.id = id;
            this.title = title;
            this.preview = preview;
            this.ink = ink;
            this.updated = updated;
            this.folderId = folderId;
            this.pinned = pinned;
            this.deleted = deleted;
        }
    }

    // --- Tasks --------------------------------------------------------------

    List<Task> listTasks() {
        List<Task> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("tasks",
                new String[]{"_id", "text", "done", "created", "done_at"},
                null, null, null, null, "done ASC, done_at DESC, created DESC")) {
            while (cursor.moveToNext()) {
                result.add(new Task(cursor.getLong(0), cursor.getString(1),
                        cursor.getInt(2) != 0, cursor.getLong(3), cursor.getLong(4)));
            }
        }
        return result;
    }

    long addTask(String text) {
        ContentValues values = new ContentValues();
        values.put("text", text.trim());
        values.put("created", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("tasks", null, values);
    }

    void setTaskText(long id, String text) {
        ContentValues values = new ContentValues();
        values.put("text", text.trim());
        updateTask(id, values);
    }

    void setTaskDone(long id, boolean done) {
        ContentValues values = new ContentValues();
        values.put("done", done ? 1 : 0);
        values.put("done_at", done ? System.currentTimeMillis() : 0);
        updateTask(id, values);
    }

    void deleteTask(long id) {
        getWritableDatabase().delete("tasks", "_id=?", new String[]{Long.toString(id)});
    }

    void deleteDoneTasks() {
        getWritableDatabase().delete("tasks", "done != 0", null);
    }

    private void updateTask(long id, ContentValues values) {
        getWritableDatabase().update("tasks", values, "_id=?", new String[]{Long.toString(id)});
    }

    static final class Task {
        final long id;
        final String text;
        final boolean done;
        final long created;
        final long doneAt;

        Task(long id, String text, boolean done, long created, long doneAt) {
            this.id = id;
            this.text = text;
            this.done = done;
            this.created = created;
            this.doneAt = doneAt;
        }
    }

    static final class Folder {
        final long id;
        final String name;

        Folder(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
