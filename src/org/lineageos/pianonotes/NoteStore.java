package org.lineageos.pianonotes;

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
    private static final int VERSION = 3;
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
        if (newVersion == 3 && (oldVersion == 1 || oldVersion == 2)) {
            if (oldVersion == 1) addOrganizing(db);
            addRichText(db);
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
                        + "folder_id, pinned, deleted "
                        + "FROM notes ORDER BY pinned DESC, updated DESC, _id DESC", null)) {
            while (cursor.moveToNext()) {
                result.add(new NoteSummary(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3) != 0, cursor.getLong(4),
                        cursor.getLong(5), cursor.getInt(6) != 0, cursor.getLong(7) != 0));
            }
        }
        return result;
    }

    Note get(long id) {
        try (Cursor cursor = getReadableDatabase().query("notes",
                new String[]{"_id", "title", "body", "is_ink", "ink_json", "updated", "spans"},
                "_id=?", new String[]{Long.toString(id)}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return new Note(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3) != 0, cursor.getString(4), cursor.getLong(5),
                    cursor.getString(6));
        }
    }

    void forEachNote(NoteConsumer consumer) throws Exception {
        try (Cursor cursor = getReadableDatabase().query("notes",
                new String[]{"_id", "title", "body", "is_ink", "ink_json", "updated", "spans"},
                null, null, null, null, "updated DESC, _id DESC")) {
            while (cursor.moveToNext()) {
                consumer.accept(new Note(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3) != 0,
                        cursor.getString(4), cursor.getLong(5), cursor.getString(6)));
            }
        }
    }

    void setPinned(long id, boolean pinned) {
        ContentValues values = new ContentValues();
        values.put("pinned", pinned ? 1 : 0);
        update(id, values);
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

    static final class Folder {
        final long id;
        final String name;

        Folder(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
