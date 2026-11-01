package com.alxp.pianonotes;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports notes from Xiaomi Notes' database ({@code note.db}), on its own or
 * inside a zip or tar backup. Folders, pins, reminders, checklists, lists and
 * text styles come across; handwriting pages and pictures cannot.
 */
final class XiaomiImport {
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\u0000".getBytes(
            java.nio.charset.StandardCharsets.US_ASCII);
    private static final int TYPE_NOTE = 0;
    private static final int TYPE_FOLDER = 1;
    private static final int TRASH_FOLDER = -3;
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z0-9-]+)([^>]*?)(/?)>");
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");

    private XiaomiImport() {}

    static final class Result {
        int notes;
        int folders;
        int skipped;
    }

    static Result run(Context context, Uri uri, NoteStore store) throws IOException {
        File database = File.createTempFile("xiaomi-notes", ".db", context.getCacheDir());
        try {
            try (InputStream in = new BufferedInputStream(
                    context.getContentResolver().openInputStream(uri))) {
                if (!extractDatabase(in, database)) {
                    throw new IOException("No Xiaomi Notes database was found in this file.");
                }
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY)) {
                return copy(db, store);
            }
        } finally {
            database.delete();
        }
    }

    /** Finds the SQLite file: the file itself, a zip entry, or a file in a tar. */
    private static boolean extractDatabase(InputStream in, File target) throws IOException {
        in.mark(512);
        byte[] head = new byte[512];
        int read = in.readNBytes(head, 0, head.length);
        in.reset();
        if (startsWith(head, 0, SQLITE_MAGIC)) {
            copy(in, target, Long.MAX_VALUE);
            return true;
        }
        if (read >= 4 && head[0] == 'P' && head[1] == 'K') {
            ZipInputStream zip = new ZipInputStream(in);
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                BufferedInputStream member = new BufferedInputStream(zip);
                member.mark(32);
                byte[] magic = member.readNBytes(SQLITE_MAGIC.length);
                member.reset();
                if (startsWith(magic, 0, SQLITE_MAGIC) && entry.getName().contains("note")) {
                    copy(member, target, Long.MAX_VALUE);
                    return true;
                }
            }
            return false;
        }
        // A tar backup: 512-byte headers, each followed by the file padded to 512 bytes.
        byte[] header = new byte[512];
        while (in.readNBytes(header, 0, 512) == 512) {
            if (header[0] == 0) break;
            String name = new String(header, 0, 100, java.nio.charset.StandardCharsets.UTF_8).trim();
            String octal = new String(header, 124, 11,
                    java.nio.charset.StandardCharsets.US_ASCII).replace('\0', ' ').trim();
            long size;
            try {
                size = octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
            } catch (NumberFormatException notTar) {
                return false;
            }
            in.mark(32);
            byte[] magic = in.readNBytes(SQLITE_MAGIC.length);
            in.reset();
            if (size > 0 && startsWith(magic, 0, SQLITE_MAGIC) && name.contains("note")
                    && !name.endsWith("-journal") && !name.endsWith("-wal")) {
                copy(in, target, size);
                return true;
            }
            in.skipNBytes((size + 511) / 512 * 512);
        }
        return false;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length - offset < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
        return true;
    }

    private static void copy(InputStream in, File target, long limit) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long left = limit;
            for (int n; left > 0 && (n = in.read(buffer, 0, (int) Math.min(buffer.length, left))) > 0; ) {
                out.write(buffer, 0, n);
                left -= n;
            }
        }
    }

    private static Result copy(SQLiteDatabase db, NoteStore store) {
        Result result = new Result();
        Map<Long, Long> folders = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT _id, snippet FROM note WHERE type = ?",
                new String[]{Integer.toString(TYPE_FOLDER)})) {
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || name.trim().isEmpty()) continue;
                folders.put(cursor.getLong(0), store.addFolder(name));
                result.folders++;
            }
        }
        boolean hasTitle = hasColumn(db, "note", "title");
        boolean hasContentType = hasColumn(db, "note", "note_content_type");
        boolean hasStick = hasColumn(db, "note", "stick_date");
        boolean hasDeletion = hasColumn(db, "note", "deletion_tag");
        String query = "SELECT n._id, n.parent_id, n.alert_date, n.modified_date, n.snippet, "
                + (hasTitle ? "n.title" : "''") + ", "
                + (hasContentType ? "n.note_content_type, n.mind_content_plain_text" : "'common', ''")
                + ", " + (hasStick ? "n.stick_date" : "0")
                + ", (SELECT content FROM data d WHERE d.note_id = n._id "
                + "AND d.mime_type LIKE '%text_note' ORDER BY d._id LIMIT 1) "
                + "FROM note n WHERE n.type = " + TYPE_NOTE
                + (hasDeletion ? " AND n.deletion_tag = 0" : "")
                + " AND n.parent_id != " + TRASH_FOLDER;
        try (Cursor cursor = db.rawQuery(query, null)) {
            while (cursor.moveToNext()) {
                String type = cursor.getString(6);
                String content = cursor.getString(9);
                Spanned body;
                if ("mind".equals(type)) {
                    body = new SpannableStringBuilder(nullToEmpty(cursor.getString(7)));
                } else if (content != null && !content.isEmpty()) {
                    body = convert(content);
                } else {
                    body = new SpannableStringBuilder(nullToEmpty(cursor.getString(4)));
                }
                String title = nullToEmpty(cursor.getString(5));
                if (title.isEmpty() && body.length() == 0) {
                    result.skipped++;
                    continue;
                }
                long id = store.save(-1, title, body.toString(), false, "",
                        RichText.serialize(body));
                Long folder = folders.get(cursor.getLong(1));
                if (folder != null) store.moveToFolder(id, folder);
                if (cursor.getLong(8) > 0) store.setPinned(id, true);
                long alert = cursor.getLong(2);
                if (alert > System.currentTimeMillis()) store.setReminder(id, alert);
                store.setUpdated(id, cursor.getLong(3));
                result.notes++;
            }
        }
        return result;
    }

    private static boolean hasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) if (column.equals(cursor.getString(1))) return true;
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Converts Xiaomi Notes' markup ({@code <text indent="1">}, {@code <b>},
     * {@code <input type="checkbox" checked="true" />}, …) into our text,
     * line markers and styles.
     */
    static Spanned convert(String markup) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        Deque<Object[]> open = new ArrayDeque<>();
        Matcher matcher = TAG.matcher(markup);
        int last = 0;
        while (matcher.find()) {
            appendText(out, markup.substring(last, matcher.start()));
            last = matcher.end();
            boolean closing = !matcher.group(1).isEmpty();
            String name = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            Map<String, String> attributes = attributes(matcher.group(3));
            boolean selfClosing = !matcher.group(4).isEmpty();
            if (closing) {
                if (name.equals("text")) {
                    out.append('\n');
                    continue;
                }
                for (Object[] entry : open) {
                    if (entry[0].equals(name)) {
                        open.remove(entry);
                        String style = styleFor(name);
                        int start = (int) entry[1];
                        if (style != null && start < out.length()) {
                            RichText.toggle(out, style, start, out.length());
                        }
                        break;
                    }
                }
                continue;
            }
            switch (name) {
                case "text":
                    int indent = parseInt(attributes.get("indent"), 1) - 1;
                    for (int i = 0; i < indent; i++) out.append(RichText.INDENT);
                    break;
                case "input":
                    out.append("true".equals(attributes.get("checked"))
                            ? RichText.CHECK_DONE : RichText.CHECK_OPEN);
                    break;
                case "bullet":
                    out.append(RichText.BULLET);
                    break;
                case "order":
                    out.append(Integer.toString(parseInt(attributes.get("inputnumber"), 0) + 1))
                            .append(". ");
                    break;
                case "hr":
                    out.append(RichText.DIVIDER);
                    break;
                default:
                    if (!selfClosing) open.push(new Object[]{name, out.length()});
            }
        }
        appendText(out, markup.substring(last));
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private static void appendText(SpannableStringBuilder out, String text) {
        if (text.isEmpty()) return;
        // Pictures are "☺ <file id>" placeholders; their files live in Xiaomi's cloud or app data.
        text = text.replaceAll("☺ ?[0-9a-zA-Z.]+", "[picture not imported]");
        out.append(text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&"));
    }

    private static String styleFor(String tag) {
        switch (tag) {
            case "b": return RichText.BOLD;
            case "u": return RichText.UNDERLINE;
            case "delete": return RichText.STRIKE;
            case "background": return RichText.HIGHLIGHT;
            case "size": return RichText.HEADING_1;
            case "mid-size": return RichText.HEADING_2;
            case "h3-size": return RichText.HEADING_2;
            default: return null;
        }
    }

    private static Map<String, String> attributes(String text) {
        Map<String, String> result = new HashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(text);
        while (matcher.find()) result.put(matcher.group(1).toLowerCase(java.util.Locale.ROOT),
                matcher.group(2));
        return result;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
