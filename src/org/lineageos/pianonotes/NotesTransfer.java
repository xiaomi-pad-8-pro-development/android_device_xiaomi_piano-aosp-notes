package org.lineageos.pianonotes;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Versioned user-controlled export format, separate from Android Auto Backup. */
final class NotesTransfer {
    private static final String FORMAT = "org.lineageos.pianonotes.backup";
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String NOTES_ENTRY = "notes.ndjson";
    private static final int VERSION = 2;
    private static final int MAX_NOTES_BYTES = 64 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_NOTES = 50_000;
    private static final int MAX_STROKES_PER_NOTE = 100_000;
    private static final int MAX_POINTS_PER_NOTE = 1_000_000;

    private NotesTransfer() { }

    static void write(Context context, Uri destination, NoteStore store) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (OutputStream raw = resolver.openOutputStream(destination, "w")) {
            if (raw == null) throw new IOException("Could not open the selected backup file.");
            try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                JSONObject manifest = new JSONObject();
                manifest.put("format", FORMAT);
                manifest.put("version", VERSION);
                manifest.put("exported_at", System.currentTimeMillis());
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();

                zip.putNextEntry(new ZipEntry(NOTES_ENTRY));
                BoundedOutputStream bounded = new BoundedOutputStream(zip, MAX_NOTES_BYTES);
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        bounded, StandardCharsets.UTF_8)) {
                    int[] count = {0};
                    store.forEachNote(note -> {
                        if (++count[0] > MAX_NOTES) {
                            throw new IOException("There are too many notes to export.");
                        }
                        if (count[0] > 1) writer.write('\n');
                        writer.write(toJson(note).toString());
                    });
                }
                zip.closeEntry();
            }
        }
    }

    static int replace(Context context, Uri source, NoteStore store) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream raw = resolver.openInputStream(source)) {
            if (raw == null) throw new IOException("Could not open the selected backup file.");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry manifestEntry = zip.getNextEntry();
                if (manifestEntry == null || manifestEntry.isDirectory()
                        || !MANIFEST_ENTRY.equals(manifestEntry.getName())) {
                    throw new IOException("This is not a Notes backup file.");
                }
                JSONObject manifest = new JSONObject(new String(
                        readBounded(zip, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
                if (!FORMAT.equals(manifest.optString("format"))
                        || manifest.optInt("version", -1) != VERSION
                        || manifest.optLong("exported_at", -1) < 0) {
                    throw new IOException("This Notes backup format is not supported.");
                }
                zip.closeEntry();

                ZipEntry notesEntry = zip.getNextEntry();
                if (notesEntry == null || notesEntry.isDirectory()
                        || !NOTES_ENTRY.equals(notesEntry.getName())) {
                    throw new IOException("The Notes backup is missing its note data.");
                }
                int[] count = {0};
                store.replaceAll(consumer -> {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            new BoundedInputStream(zip, MAX_NOTES_BYTES), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) throw new IOException("The backup contains an empty record.");
                        if (++count[0] > MAX_NOTES) {
                            throw new IOException("The backup contains too many notes.");
                        }
                        consumer.accept(parseNote(line));
                    }
                    zip.closeEntry();
                    if (zip.getNextEntry() != null) {
                        throw new IOException("The backup contains unexpected files.");
                    }
                });
                return count[0];
            }
        }
    }

    private static JSONObject toJson(NoteStore.Note note) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("title", note.title);
        item.put("body", note.body);
        item.put("ink", note.ink);
        item.put("ink_json", note.inkJson);
        item.put("updated", note.updated);
        return item;
    }

    private static NoteStore.Note parseNote(String json) throws IOException, JSONException {
        JSONObject item = new JSONObject(json);
        String title = item.getString("title");
        String body = item.getString("body");
        boolean ink = item.getBoolean("ink");
        String inkJson = item.getString("ink_json");
        long updated = item.getLong("updated");
        if (title.length() > 100_000 || updated < 0) {
            throw new IOException("The backup contains invalid note data.");
        }
        if (ink) validateInk(inkJson);
        else if (!inkJson.isEmpty()) throw new IOException("The backup contains invalid note data.");
        return new NoteStore.Note(-1, title, body, ink, inkJson, updated);
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("The backup manifest is too large.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void validateInk(String json) throws IOException, JSONException {
        org.json.JSONArray strokes = new org.json.JSONArray(json);
        if (strokes.length() > MAX_STROKES_PER_NOTE) {
            throw new IOException("The backup contains too many ink strokes.");
        }
        int pointCount = 0;
        for (int i = 0; i < strokes.length(); i++) {
            org.json.JSONArray points = strokes.getJSONArray(i);
            pointCount += points.length();
            if (pointCount > MAX_POINTS_PER_NOTE) {
                throw new IOException("The backup contains too many ink points.");
            }
            for (int j = 0; j < points.length(); j++) {
                JSONObject point = points.getJSONObject(j);
                double x = point.getDouble("x");
                double y = point.getDouble("y");
                double pressure = point.getDouble("p");
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(pressure)
                        || x < 0 || x > NotesActivity.InkView.PAGE_WIDTH
                        || y < 0 || y > NotesActivity.InkView.PAGE_HEIGHT
                        || pressure < 0 || pressure > 1) {
                    throw new IOException("The backup contains invalid ink data.");
                }
            }
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream output;
        private final int maxBytes;
        private int count;

        BoundedOutputStream(OutputStream output, int maxBytes) {
            this.output = output;
            this.maxBytes = maxBytes;
        }

        @Override public void write(int value) throws IOException {
            ensureCapacity(1);
            output.write(value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            output.write(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(int length) throws IOException {
            if (length < 0 || count > maxBytes - length) {
                throw new IOException("Notes export exceeds the 64 MiB limit.");
            }
        }

        @Override public void flush() throws IOException { output.flush(); }

        @Override public void close() throws IOException {
            // Finalize the entry's UTF-8 writer without closing the enclosing ZIP stream.
            flush();
        }
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream input;
        private final int maxBytes;
        private int count;

        BoundedInputStream(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
        }

        @Override public int read() throws IOException {
            if (count == maxBytes) {
                if (input.read() == -1) return -1;
                throw new IOException("The backup note data exceeds the 64 MiB limit.");
            }
            int value = input.read();
            if (value != -1) count++;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (count == maxBytes) {
                if (input.read() == -1) return -1;
                throw new IOException("The backup note data exceeds the 64 MiB limit.");
            }
            int allowed = Math.min(length, maxBytes - count);
            int read = input.read(bytes, offset, allowed);
            if (read > 0) count += read;
            return read;
        }
    }
}
