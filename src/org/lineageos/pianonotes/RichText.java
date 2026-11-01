package org.lineageos.pianonotes;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Character styles of a text note, kept next to its plain text. Line styles
 * (lists, checklists, indents, dividers) live in the text itself as line
 * prefixes, so the plain text still reads well in previews and backups.
 */
final class RichText {
    static final String BOLD = "bold";
    static final String HIGHLIGHT = "highlight";
    static final String HEADING_1 = "h1";
    static final String HEADING_2 = "h2";
    static final String STRIKE = "strike";
    static final String UNDERLINE = "underline";

    static final String BULLET = "• ";
    static final String CHECK_OPEN = "☐ ";
    static final String CHECK_DONE = "☑ ";
    static final String INDENT = "    ";
    static final String DIVIDER = "────────────────";

    static final int HIGHLIGHT_COLOR = 0x66ffd54f;

    private RichText() {}

    static String serialize(Spanned text) {
        JSONArray spans = new JSONArray();
        for (Object span : text.getSpans(0, text.length(), Object.class)) {
            String type = typeOf(span);
            if (type == null) continue;
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            if (start >= end) continue;
            try {
                spans.put(new JSONObject().put("t", type).put("s", start).put("e", end));
            } catch (JSONException ignored) {
                // Only strings and ints are written.
            }
        }
        return spans.length() == 0 ? "" : spans.toString();
    }

    static void apply(Editable text, String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray spans = new JSONArray(json);
            for (int i = 0; i < spans.length(); i++) {
                JSONObject span = spans.getJSONObject(i);
                int start = Math.max(0, span.getInt("s"));
                int end = Math.min(text.length(), span.getInt("e"));
                if (start < end) add(text, span.getString("t"), start, end);
            }
        } catch (JSONException ignored) {
            // A damaged style list leaves the plain text untouched.
        }
    }

    /** Turns a style on for the range, or off where the whole range already has it. */
    static void toggle(Editable text, String type, int start, int end) {
        if (start >= end) return;
        boolean covered = false;
        for (Object span : text.getSpans(start, end, Object.class)) {
            if (type.equals(typeOf(span)) && text.getSpanStart(span) <= start
                    && text.getSpanEnd(span) >= end) {
                covered = true;
                int spanStart = text.getSpanStart(span);
                int spanEnd = text.getSpanEnd(span);
                text.removeSpan(span);
                if (spanStart < start) add(text, type, spanStart, start);
                if (spanEnd > end) add(text, type, end, spanEnd);
            }
        }
        if (!covered) add(text, type, start, end);
    }

    private static void add(Editable text, String type, int start, int end) {
        Object span = create(type);
        if (span != null) text.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
    }

    private static Object create(String type) {
        switch (type) {
            case BOLD: return new StyleSpan(Typeface.BOLD);
            case HIGHLIGHT: return new BackgroundColorSpan(HIGHLIGHT_COLOR);
            case HEADING_1: return new HeadingSpan(1.6f);
            case HEADING_2: return new HeadingSpan(1.3f);
            case STRIKE: return new StrikethroughSpan();
            case UNDERLINE: return new UnderlineSpan();
            default: return null;
        }
    }

    private static String typeOf(Object span) {
        if (span instanceof HeadingSpan) {
            return ((HeadingSpan) span).getSizeChange() > 1.4f ? HEADING_1 : HEADING_2;
        }
        if (span instanceof StyleSpan && ((StyleSpan) span).getStyle() == Typeface.BOLD) return BOLD;
        if (span instanceof BackgroundColorSpan) return HIGHLIGHT;
        if (span instanceof StrikethroughSpan) return STRIKE;
        if (span instanceof UnderlineSpan && span.getClass() == UnderlineSpan.class) return UNDERLINE;
        return null;
    }

    /** Larger, bold text for headings. */
    static final class HeadingSpan extends RelativeSizeSpan {
        HeadingSpan(float size) {
            super(size);
        }

        @Override public void updateDrawState(android.text.TextPaint paint) {
            super.updateDrawState(paint);
            paint.setFakeBoldText(true);
        }

        @Override public void updateMeasureState(android.text.TextPaint paint) {
            super.updateMeasureState(paint);
            paint.setFakeBoldText(true);
        }
    }

    // --- Line prefixes ------------------------------------------------------

    static int lineStart(CharSequence text, int offset) {
        int i = Math.min(offset, text.length());
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    static int lineEnd(CharSequence text, int offset) {
        int i = offset;
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
    }

    /** The list marker a line starts with, after its indent, or null. */
    static String markerOf(CharSequence line) {
        String trimmed = line.toString().replaceFirst("^( {4})*", "");
        if (trimmed.startsWith(BULLET)) return BULLET;
        if (trimmed.startsWith(CHECK_OPEN)) return CHECK_OPEN;
        if (trimmed.startsWith(CHECK_DONE)) return CHECK_DONE;
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("^\\d+\\. ").matcher(trimmed);
        return number.find() ? number.group() : null;
    }

    static int indentOf(CharSequence line) {
        int i = 0;
        while (line.length() >= i + INDENT.length()
                && line.subSequence(i, i + INDENT.length()).toString().equals(INDENT)) {
            i += INDENT.length();
        }
        return i;
    }
}
