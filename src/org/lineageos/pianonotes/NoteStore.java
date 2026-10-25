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
    private static final int VERSION = 1;

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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Fail closed until an explicit forward migration is added. SQLiteOpenHelper
        // runs this callback transactionally, so throwing preserves the old schema
        // version and prevents the app from silently treating unmigrated notes as current.
        throw new SQLiteException("No notes database migration from schema " + oldVersion
                + " to " + newVersion + ".");
    }

    long save(long id, String title, String body, boolean ink, String inkJson) {
        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("title", title == null ? "" : title.trim());
        values.put("body", body == null ? "" : body);
        values.put("is_ink", ink ? 1 : 0);
        values.put("ink_json", inkJson == null ? "" : inkJson);
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
                        + "ELSE substr(body, 1, 90) END, is_ink, updated "
                        + "FROM notes ORDER BY updated DESC, _id DESC", null)) {
            while (cursor.moveToNext()) {
                result.add(new NoteSummary(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3) != 0, cursor.getLong(4)));
            }
        }
        return result;
    }

    Note get(long id) {
        try (Cursor cursor = getReadableDatabase().query("notes",
                new String[]{"_id", "title", "body", "is_ink", "ink_json", "updated"},
                "_id=?", new String[]{Long.toString(id)}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return new Note(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getInt(3) != 0, cursor.getString(4), cursor.getLong(5));
        }
    }

    void forEachNote(NoteConsumer consumer) throws Exception {
        try (Cursor cursor = getReadableDatabase().query("notes",
                new String[]{"_id", "title", "body", "is_ink", "ink_json", "updated"},
                null, null, null, null, "updated DESC, _id DESC")) {
            while (cursor.moveToNext()) {
                consumer.accept(new Note(cursor.getLong(0), cursor.getString(1),
                        cursor.getString(2), cursor.getInt(3) != 0,
                        cursor.getString(4), cursor.getLong(5)));
            }
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

        Note(long id, String title, String body, boolean ink, String inkJson, long updated) {
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

        NoteSummary(long id, String title, String preview, boolean ink, long updated) {
            this.id = id;
            this.title = title;
            this.preview = preview;
            this.ink = ink;
            this.updated = updated;
        }
    }
}
