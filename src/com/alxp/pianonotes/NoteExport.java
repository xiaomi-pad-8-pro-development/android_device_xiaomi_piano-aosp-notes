package com.alxp.pianonotes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.View;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Share a note as an image, a PDF or Markdown, like Xiaomi Notes' export. */
final class NoteExport {
    static final int IMAGE = 0;
    static final int PDF = 1;
    static final int MARKDOWN = 2;

    /** A4 at 72 dpi, the PdfDocument unit. */
    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 40;

    private NoteExport() {}

    static String mimeType(int kind) {
        return kind == IMAGE ? "image/png" : kind == PDF ? "application/pdf" : "text/markdown";
    }

    static String fileName(String title, int kind) {
        String base = title == null || title.trim().isEmpty() ? "Note"
                : title.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        return base + (kind == IMAGE ? ".png" : kind == PDF ? ".pdf" : ".md");
    }

    /** Draws a text note on a white page as wide as the given width. */
    static Bitmap renderText(String title, Spanned body, int width, float density) {
        int pad = (int) (24 * density);
        TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(24 * density);
        titlePaint.setFakeBoldText(true);
        TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(0xff202124);
        bodyPaint.setTextSize(16 * density);
        int inner = width - 2 * pad;
        StaticLayout titleLayout = layout(title == null ? "" : title, titlePaint, inner);
        StaticLayout bodyLayout = layout(body, bodyPaint, inner);
        int gap = title == null || title.isEmpty() ? 0 : (int) (12 * density);
        int height = pad * 2 + titleLayout.getHeight() + gap + bodyLayout.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        canvas.translate(pad, pad);
        titleLayout.draw(canvas);
        canvas.translate(0, titleLayout.getHeight() + gap);
        bodyLayout.draw(canvas);
        return bitmap;
    }

    /** Draws a view, such as a handwriting page, on white. */
    static Bitmap renderView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, view.getWidth()),
                Math.max(1, view.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        view.draw(canvas);
        return bitmap;
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, 1.15f)
                .build();
    }

    static void writeImage(Bitmap bitmap, OutputStream out) throws IOException {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            throw new IOException("Could not encode the image.");
        }
    }

    /** Scales the picture to the page width and splits it over as many A4 pages as needed. */
    static void writePdf(Bitmap bitmap, OutputStream out) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            int inner = PAGE_WIDTH - 2 * MARGIN;
            float scale = inner / (float) bitmap.getWidth();
            int sliceHeight = (int) ((PAGE_HEIGHT - 2 * MARGIN) / scale);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            int pageNumber = 1;
            for (int top = 0; top < bitmap.getHeight(); top += sliceHeight) {
                int bottom = Math.min(bitmap.getHeight(), top + sliceHeight);
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, pageNumber++).create());
                Rect source = new Rect(0, top, bitmap.getWidth(), bottom);
                Rect target = new Rect(MARGIN, MARGIN, MARGIN + inner,
                        MARGIN + (int) ((bottom - top) * scale));
                page.getCanvas().drawBitmap(bitmap, source, target, paint);
                document.finishPage(page);
            }
            document.writeTo(out);
        } finally {
            document.close();
        }
    }

    static void writeMarkdown(String title, Spanned body, OutputStream out) throws IOException {
        out.write(markdown(title, body).getBytes(StandardCharsets.UTF_8));
    }

    /** Turns the note's line markers and styles into Markdown. */
    static String markdown(String title, Spanned body) {
        StringBuilder result = new StringBuilder();
        if (title != null && !title.trim().isEmpty()) result.append("# ").append(title.trim()).append("\n\n");
        String text = body.toString();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            result.append(markdownLine(body, start, end)).append('\n');
            start = end + 1;
        }
        return result.toString();
    }

    private static String markdownLine(Spanned body, int start, int end) {
        String line = body.subSequence(start, end).toString();
        if (line.equals(RichText.DIVIDER)) return "---";
        int indent = RichText.indentOf(line);
        String marker = RichText.markerOf(line);
        int textStart = start + indent + (marker == null ? 0 : marker.length());
        String prefix = "  ".repeat(indent / RichText.INDENT.length());
        if (RichText.BULLET.equals(marker)) prefix += "- ";
        else if (RichText.CHECK_OPEN.equals(marker)) prefix += "- [ ] ";
        else if (RichText.CHECK_DONE.equals(marker)) prefix += "- [x] ";
        else if (marker != null) prefix += marker;
        String heading = "";
        for (RichText.HeadingSpan span : body.getSpans(textStart, end, RichText.HeadingSpan.class)) {
            if (body.getSpanStart(span) <= textStart) {
                heading = span.getSizeChange() > 1.4f ? "## " : "### ";
            }
        }
        StringBuilder text = new StringBuilder();
        for (int i = textStart; i < end; i++) {
            text.append(markers(body, i, true));
            text.append(body.charAt(i));
            text.append(markers(body, i + 1, false));
        }
        return prefix + heading + text;
    }

    /** Bold and strikethrough markers that open at or close just before an offset. */
    private static String markers(Spanned body, int at, boolean opening) {
        StringBuilder out = new StringBuilder();
        for (Object span : body.getSpans(Math.max(0, at - 1), Math.min(body.length(), at + 1),
                Object.class)) {
            int edge = opening ? body.getSpanStart(span) : body.getSpanEnd(span);
            if (edge != at) continue;
            if (span instanceof StyleSpan
                    && ((StyleSpan) span).getStyle() == android.graphics.Typeface.BOLD) {
                out.append("**");
            } else if (span instanceof StrikethroughSpan) {
                out.append("~~");
            }
        }
        return out.toString();
    }
}
