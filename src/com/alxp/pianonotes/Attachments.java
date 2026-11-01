package com.alxp.pianonotes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures and recordings inside text notes. Each one is a line such as
 * {@code [image:1f3c.jpg]} in the note text, drawn as the picture or as an
 * audio chip; the files live in the app's attachments folder.
 */
final class Attachments {
    static final String IMAGE = "image";
    static final String AUDIO = "audio";
    private static final Pattern TOKEN = Pattern.compile("\\[(image|audio):([A-Za-z0-9._-]+)\\]");

    private Attachments() {}

    static File folder(Context context) {
        File folder = new File(context.getFilesDir(), "attachments");
        folder.mkdirs();
        return folder;
    }

    static File file(Context context, String name) {
        return new File(folder(context), name);
    }

    static String newName(String extension) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "." + extension;
    }

    static String token(String kind, String name) {
        return "[" + kind + ":" + name + "]";
    }

    /** Copies a picked picture into the attachments folder, scaled down to at most 2048 px. */
    static String importImage(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) throw new IOException("This file is not a picture.");
        String name = newName("jpg");
        try (OutputStream out = new FileOutputStream(file(context, name))) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        return name;
    }

    /** Draws every attachment line in the text as its picture or audio chip. */
    static void render(Context context, Editable text, int maxWidth) {
        for (AttachmentSpan span : text.getSpans(0, text.length(), AttachmentSpan.class)) {
            text.removeSpan(span);
        }
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String kind = matcher.group(1);
            String name = matcher.group(2);
            AttachmentSpan span = IMAGE.equals(kind)
                    ? new AttachmentSpan(kind, name, thumbnail(file(context, name), maxWidth), null)
                    : new AttachmentSpan(kind, name, null, audioLabel(context, name));
            text.setSpan(span, matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    static AttachmentSpan at(Spanned text, int offset) {
        for (AttachmentSpan span : text.getSpans(Math.max(0, offset - 1),
                Math.min(text.length(), offset + 1), AttachmentSpan.class)) {
            if (text.getSpanStart(span) <= offset && text.getSpanEnd(span) >= offset) return span;
        }
        return null;
    }

    /** Names of the files a note uses, so backups can carry them. */
    static List<String> names(CharSequence text) {
        List<String> names = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) names.add(matcher.group(2));
        return names;
    }

    private static Bitmap thumbnail(File file, int maxWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= maxWidth) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null || bitmap.getWidth() <= maxWidth) return bitmap;
        int height = bitmap.getHeight() * maxWidth / bitmap.getWidth();
        return Bitmap.createScaledBitmap(bitmap, maxWidth, Math.max(1, height), true);
    }

    // --- Recordings ---------------------------------------------------------

    static File bookmarksFile(Context context, String audioName) {
        return file(context, audioName + ".json");
    }

    static List<Long> bookmarks(Context context, String audioName) {
        List<Long> marks = new ArrayList<>();
        try {
            byte[] json = Files.readAllBytes(bookmarksFile(context, audioName).toPath());
            JSONArray array = new JSONArray(new String(json, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) marks.add(array.getLong(i));
        } catch (IOException | JSONException ignored) {
            // No bookmarks yet.
        }
        return marks;
    }

    static void saveBookmarks(Context context, String audioName, List<Long> marks)
            throws IOException {
        JSONArray array = new JSONArray();
        for (long mark : marks) array.put(mark);
        Files.write(bookmarksFile(context, audioName).toPath(),
                array.toString().getBytes(StandardCharsets.UTF_8));
    }

    static long duration(Context context, String audioName) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(file(context, audioName).getPath());
            String value = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException failure) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Nothing to release.
            }
        }
    }

    static String time(long ms) {
        long seconds = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String audioLabel(Context context, String name) {
        int marks = bookmarks(context, name).size();
        return "▶  Recording · " + time(duration(context, name))
                + (marks == 0 ? "" : " · " + marks + (marks == 1 ? " bookmark" : " bookmarks"));
    }

    /** Draws a picture, or a rounded audio chip, in place of an attachment line. */
    static final class AttachmentSpan extends ReplacementSpan {
        final String kind;
        final String name;
        private final Bitmap bitmap;
        private final String label;

        AttachmentSpan(String kind, String name, Bitmap bitmap, String label) {
            this.kind = kind;
            this.name = name;
            this.bitmap = bitmap;
            this.label = label == null ? "Missing picture" : label;
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt fm) {
            int width;
            int height;
            if (bitmap != null) {
                width = bitmap.getWidth();
                height = bitmap.getHeight();
            } else {
                width = (int) paint.measureText(label) + (int) (paint.getTextSize() * 2);
                height = (int) (paint.getTextSize() * 2.4f);
            }
            if (fm != null) {
                fm.ascent = fm.top = -height;
                fm.descent = fm.bottom = 0;
            }
            return width;
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                int top, int y, int bottom, Paint paint) {
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, x, y - bitmap.getHeight(), null);
                return;
            }
            float height = paint.getTextSize() * 2.4f;
            float width = paint.measureText(label) + paint.getTextSize() * 2;
            int color = paint.getColor();
            paint.setColor((color & 0x00ffffff) | 0x1f000000);
            canvas.drawRoundRect(new RectF(x, y - height, x + width, y), height / 2,
                    height / 2, paint);
            paint.setColor(color);
            canvas.drawText(label, x + paint.getTextSize(), y - height * 0.35f, paint);
        }
    }
}
