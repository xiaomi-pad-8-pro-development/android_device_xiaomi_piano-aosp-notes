package com.alxp.pianonotes;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.material.color.MaterialColors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A handwriting page with the Xiaomi Notes tools: seven pens with their own
 * look, stroke and pixel erasers, lasso select-and-move, a ruler for straight
 * lines, an eyedropper and undo/redo.
 */
final class InkView extends View {
    static final float PAGE_WIDTH = 1000f;
    static final float PAGE_HEIGHT = 1414f;
    private static final int MAX_HISTORY = 50;

    enum Pen { BALLPOINT, FOUNTAIN, PENCIL, MARKER, HIGHLIGHTER, WATERCOLOR, CRAYON }

    enum Mode { DRAW, STROKE_ERASER, PIXEL_ERASER, LASSO, EYEDROPPER }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Stroke> strokes = new ArrayList<>();
    private final List<String> undo = new ArrayList<>();
    private final List<String> redo = new ArrayList<>();
    private final Runnable onChanged;
    private final int pageColor;
    private final int themeInk;

    private Pen pen = Pen.BALLPOINT;
    private int color;
    private float width = 4f;
    private int alpha = 255;
    private Mode mode = Mode.DRAW;
    private float eraserRadius = 22f;
    private boolean ruler;
    private IntConsumer onColorPicked;

    private Stroke active;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private int activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
    private boolean erasingGesture;
    private boolean changedInGesture;

    private Path lasso;
    private final List<Stroke> selection = new ArrayList<>();
    private RectF selectionBounds;
    private float dragX;
    private float dragY;
    private boolean dragging;

    InkView(Activity context, Runnable onChanged) {
        super(context);
        this.onChanged = onChanged;
        setBackgroundColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceContainer, Color.LTGRAY));
        pageColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurface, Color.WHITE);
        themeInk = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY);
        color = themeInk;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    // --- Tool state -----------------------------------------------------------

    void setPen(Pen pen, int color, float width, int alpha) {
        this.pen = pen;
        this.color = color;
        this.width = width;
        this.alpha = alpha;
        setMode(Mode.DRAW);
    }

    void setMode(Mode mode) {
        this.mode = mode;
        if (mode != Mode.LASSO) clearSelection();
        invalidate();
    }

    Mode mode() {
        return mode;
    }

    void setEraserRadius(float radius) {
        eraserRadius = radius;
    }

    void setRuler(boolean on) {
        ruler = on;
    }

    boolean ruler() {
        return ruler;
    }

    /** The next tap on the page reports the colour under it. */
    void pickColor(IntConsumer listener) {
        onColorPicked = listener;
        setMode(Mode.EYEDROPPER);
    }

    boolean hasSelection() {
        return !selection.isEmpty();
    }

    void deleteSelection() {
        if (selection.isEmpty()) return;
        snapshot();
        strokes.removeAll(selection);
        clearSelection();
        changed();
    }

    void recolorSelection(int newColor) {
        if (selection.isEmpty()) return;
        snapshot();
        for (Stroke stroke : selection) stroke.color = newColor;
        changed();
    }

    private void clearSelection() {
        selection.clear();
        selectionBounds = null;
        lasso = null;
    }

    // --- Drawing --------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = pageScale();
        canvas.save();
        canvas.translate(pageLeft(), pageTop());
        canvas.scale(scale, scale);
        drawPage(canvas);
        if (lasso != null || selectionBounds != null) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f / scale);
            paint.setColor(MaterialColors.getColor(this,
                    androidx.appcompat.R.attr.colorPrimary, Color.BLUE));
            paint.setPathEffect(new DashPathEffect(new float[]{12f / scale, 8f / scale}, 0));
            if (lasso != null) canvas.drawPath(lasso, paint);
            if (selectionBounds != null) canvas.drawRect(selectionBounds, paint);
            paint.setPathEffect(null);
        }
        canvas.restore();
    }

    private void drawPage(Canvas canvas) {
        paint.reset();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pageColor);
        canvas.drawRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT, paint);
        for (Stroke stroke : strokes) drawStroke(canvas, stroke);
        if (active != null) drawStroke(canvas, active);
    }

    private void drawStroke(Canvas canvas, Stroke stroke) {
        int n = stroke.points.size();
        if (n == 0) return;
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        int ink = stroke.color == 0 ? themeInk : stroke.color;
        paint.setColor(ink);
        float w = stroke.width;
        switch (stroke.pen) {
            case BALLPOINT:
            case FOUNTAIN:
                drawPressureStroke(canvas, stroke, ink, stroke.alpha,
                        stroke.pen == Pen.FOUNTAIN ? 1.4f : 0.3f);
                return;
            case PENCIL:
                paint.setAlpha(Math.min(stroke.alpha, 200));
                paint.setStrokeWidth(Math.max(1f, w * 0.8f));
                break;
            case MARKER:
                paint.setAlpha(Math.min(stroke.alpha, 235));
                paint.setStrokeWidth(w * 1.6f);
                paint.setStrokeCap(Paint.Cap.SQUARE);
                break;
            case HIGHLIGHTER:
                paint.setAlpha(Math.min(stroke.alpha, 90));
                paint.setStrokeWidth(w * 4f);
                paint.setStrokeCap(Paint.Cap.BUTT);
                break;
            case WATERCOLOR:
                paint.setAlpha(Math.min(stroke.alpha, 70));
                paint.setStrokeWidth(w * 3f);
                paint.setMaskFilter(new BlurMaskFilter(w * 1.2f, BlurMaskFilter.Blur.NORMAL));
                break;
            case CRAYON:
                paint.setAlpha(Math.min(stroke.alpha, 220));
                paint.setStrokeWidth(w * 1.8f);
                paint.setPathEffect(new DashPathEffect(new float[]{w * 0.6f, w * 0.25f}, 0));
                break;
        }
        paint.setStyle(Paint.Style.STROKE);
        // One path per stroke, so see-through pens do not darken where they overlap themselves.
        canvas.drawPath(stroke.path(), paint);
    }

    private void drawPressureStroke(Canvas canvas, Stroke stroke, int ink, int strokeAlpha,
            float pressureWeight) {
        paint.setColor(ink);
        paint.setAlpha(strokeAlpha);
        paint.setStyle(Paint.Style.STROKE);
        List<float[]> p = stroke.points;
        if (p.size() == 1) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(p.get(0)[0], p.get(0)[1], stroke.width / 2f, paint);
            return;
        }
        for (int i = 1; i < p.size(); i++) {
            float pressure = Math.max(0.05f, Math.min(1.5f, p.get(i)[2]));
            float factor = 1f - pressureWeight / 2f + pressureWeight * pressure;
            paint.setStrokeWidth(Math.max(0.8f, stroke.width * factor));
            canvas.drawLine(p.get(i - 1)[0], p.get(i - 1)[1], p.get(i)[0], p.get(i)[1], paint);
        }
    }

    // --- Touch ----------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!insidePage(event.getX(0), event.getY(0))) return false;
            begin(event, 0);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int toolType = event.getToolType(index);
            // A stylus that lands while a palm or finger is down takes over.
            if (activeToolType == MotionEvent.TOOL_TYPE_FINGER
                    && (toolType == MotionEvent.TOOL_TYPE_STYLUS
                    || toolType == MotionEvent.TOOL_TYPE_ERASER)
                    && insidePage(event.getX(index), event.getY(index))) {
                if (active != null && !active.points.isEmpty()) strokes.add(active);
                begin(event, index);
            }
            return true;
        }
        int pointer = event.findPointerIndex(activePointerId);
        if (action == MotionEvent.ACTION_MOVE && pointer >= 0) {
            for (int h = 0; h < event.getHistorySize(); h++) {
                move(event.getHistoricalX(pointer, h), event.getHistoricalY(pointer, h),
                        event.getHistoricalPressure(pointer, h));
            }
            move(event.getX(pointer), event.getY(pointer), event.getPressure(pointer));
            invalidate();
            return true;
        }
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL)
                && (action == MotionEvent.ACTION_CANCEL
                || event.getPointerId(index) == activePointerId)) {
            end();
            return true;
        }
        return true;
    }

    private Mode gestureMode(int toolType) {
        if (toolType == MotionEvent.TOOL_TYPE_ERASER) return Mode.STROKE_ERASER;
        return mode;
    }

    private void begin(MotionEvent event, int index) {
        activePointerId = event.getPointerId(index);
        activeToolType = event.getToolType(index);
        float x = toPageX(event.getX(index));
        float y = toPageY(event.getY(index));
        Mode gesture = gestureMode(activeToolType);
        erasingGesture = gesture == Mode.STROKE_ERASER || gesture == Mode.PIXEL_ERASER;
        changedInGesture = false;
        active = null;
        if (gesture == Mode.EYEDROPPER) {
            int picked = colorAt(x, y);
            if (onColorPicked != null) onColorPicked.accept(picked);
            onColorPicked = null;
            mode = Mode.DRAW;
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            return;
        }
        if (gesture == Mode.LASSO) {
            if (selectionBounds != null && selectionBounds.contains(x, y)) {
                snapshot();
                dragging = true;
                dragX = x;
                dragY = y;
            } else {
                clearSelection();
                lasso = new Path();
                lasso.moveTo(x, y);
            }
            invalidate();
            return;
        }
        if (erasingGesture) {
            snapshot();
            erase(x, y, gesture);
            invalidate();
            return;
        }
        snapshot();
        active = new Stroke(pen, color, width, alpha);
        active.points.add(new float[]{x, y, event.getPressure(index)});
        invalidate();
    }

    private void move(float screenX, float screenY, float pressure) {
        float x = toPageX(screenX);
        float y = toPageY(screenY);
        Mode gesture = gestureMode(activeToolType);
        if (gesture == Mode.LASSO) {
            if (dragging) {
                float dx = x - dragX;
                float dy = y - dragY;
                for (Stroke stroke : selection) stroke.offset(dx, dy);
                selectionBounds.offset(dx, dy);
                dragX = x;
                dragY = y;
                changedInGesture = true;
            } else if (lasso != null) {
                lasso.lineTo(x, y);
            }
            return;
        }
        if (erasingGesture) {
            erase(x, y, gesture);
            return;
        }
        if (active == null) return;
        if (ruler && active.points.size() > 1) {
            // Ruler: the stroke stays a straight line from its first point.
            active.points.subList(1, active.points.size()).clear();
        }
        active.points.add(new float[]{x, y, pressure});
    }

    private void end() {
        Mode gesture = gestureMode(activeToolType);
        if (gesture == Mode.LASSO) {
            if (!dragging && lasso != null) selectInside(lasso);
            dragging = false;
            lasso = null;
        } else if (active != null && !active.points.isEmpty()) {
            strokes.add(active);
            changedInGesture = true;
        }
        active = null;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        erasingGesture = false;
        if (changedInGesture) changed();
        else if (!undo.isEmpty() && gesture != Mode.LASSO) undo.remove(undo.size() - 1);
        invalidate();
    }

    private void selectInside(Path outline) {
        outline.close();
        RectF bounds = new RectF();
        outline.computeBounds(bounds, true);
        Region region = new Region();
        region.setPath(outline, new Region((int) bounds.left, (int) bounds.top,
                (int) Math.ceil(bounds.right), (int) Math.ceil(bounds.bottom)));
        selection.clear();
        RectF selected = null;
        for (Stroke stroke : strokes) {
            int inside = 0;
            for (float[] p : stroke.points) if (region.contains((int) p[0], (int) p[1])) inside++;
            if (inside * 2 > stroke.points.size()) {
                selection.add(stroke);
                RectF b = stroke.bounds();
                if (selected == null) selected = new RectF(b); else selected.union(b);
            }
        }
        selectionBounds = selected;
    }

    private void erase(float x, float y, Mode gesture) {
        float radius = eraserRadius;
        float r2 = radius * radius;
        for (int i = strokes.size() - 1; i >= 0; i--) {
            Stroke stroke = strokes.get(i);
            if (!stroke.near(x, y, r2)) continue;
            changedInGesture = true;
            if (gesture == Mode.STROKE_ERASER) {
                strokes.remove(i);
                return;
            }
            // Pixel eraser: cut the points under the eraser out of the stroke.
            strokes.remove(i);
            Stroke piece = null;
            List<Stroke> pieces = new ArrayList<>();
            for (float[] p : stroke.points) {
                float dx = p[0] - x;
                float dy = p[1] - y;
                if (dx * dx + dy * dy <= r2) {
                    piece = null;
                } else {
                    if (piece == null) {
                        piece = stroke.copyStyle();
                        pieces.add(piece);
                    }
                    piece.points.add(p);
                }
            }
            strokes.addAll(i, pieces);
        }
    }

    /** The colour drawn at a page point, or the page colour where nothing is drawn. */
    private int colorAt(float x, float y) {
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(-x, -y);
        drawPage(canvas);
        int picked = bitmap.getPixel(0, 0) | 0xff000000;
        bitmap.recycle();
        return picked;
    }

    // --- Page geometry --------------------------------------------------------

    private float pageScale() {
        if (getWidth() <= 0 || getHeight() <= 0) return 1f;
        return Math.min(getWidth() / PAGE_WIDTH, getHeight() / PAGE_HEIGHT);
    }

    private float pageLeft() {
        return (getWidth() - PAGE_WIDTH * pageScale()) / 2f;
    }

    private float pageTop() {
        return (getHeight() - PAGE_HEIGHT * pageScale()) / 2f;
    }

    private boolean insidePage(float x, float y) {
        float scale = pageScale();
        return x >= pageLeft() && x <= pageLeft() + PAGE_WIDTH * scale
                && y >= pageTop() && y <= pageTop() + PAGE_HEIGHT * scale;
    }

    private float toPageX(float x) {
        return Math.max(0f, Math.min(PAGE_WIDTH, (x - pageLeft()) / pageScale()));
    }

    private float toPageY(float y) {
        return Math.max(0f, Math.min(PAGE_HEIGHT, (y - pageTop()) / pageScale()));
    }

    // --- History and storage --------------------------------------------------

    private void snapshot() {
        undo.add(serialize());
        if (undo.size() > MAX_HISTORY) undo.remove(0);
        redo.clear();
    }

    private void changed() {
        invalidate();
        onChanged.run();
    }

    void undo() {
        if (undo.isEmpty()) return;
        redo.add(serialize());
        load(undo.remove(undo.size() - 1));
        clearSelection();
        changed();
    }

    void redo() {
        if (redo.isEmpty()) return;
        undo.add(serialize());
        load(redo.remove(redo.size() - 1));
        clearSelection();
        changed();
    }

    void clear() {
        snapshot();
        strokes.clear();
        clearSelection();
        active = null;
        changed();
    }

    boolean hasInk() {
        return !strokes.isEmpty() || active != null;
    }

    String serialize() {
        JSONArray array = new JSONArray();
        for (Stroke stroke : strokes) array.put(stroke.toJson());
        if (active != null && !active.points.isEmpty()) array.put(active.toJson());
        return array.toString();
    }

    void restore(String json) {
        undo.clear();
        redo.clear();
        load(json);
        invalidate();
    }

    private void load(String json) {
        strokes.clear();
        active = null;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) strokes.add(Stroke.fromJson(array.get(i)));
        } catch (JSONException ignored) {
            // An unreadable page opens empty rather than crashing the editor.
        }
    }

    /** One stroke and the pen it was drawn with. Pages from before pens were points only. */
    static final class Stroke {
        Pen pen;
        int color;
        float width;
        int alpha;
        final List<float[]> points = new ArrayList<>();

        Stroke(Pen pen, int color, float width, int alpha) {
            this.pen = pen;
            this.color = color;
            this.width = width;
            this.alpha = alpha;
        }

        Stroke copyStyle() {
            return new Stroke(pen, color, width, alpha);
        }

        Path path() {
            Path path = new Path();
            if (points.isEmpty()) return path;
            path.moveTo(points.get(0)[0], points.get(0)[1]);
            if (points.size() == 1) path.lineTo(points.get(0)[0] + 0.1f, points.get(0)[1]);
            for (int i = 1; i < points.size(); i++) {
                float[] a = points.get(i - 1);
                float[] b = points.get(i);
                // Smooth through the midpoints.
                path.quadTo(a[0], a[1], (a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f);
            }
            float[] last = points.get(points.size() - 1);
            path.lineTo(last[0], last[1]);
            return path;
        }

        RectF bounds() {
            RectF b = new RectF(points.get(0)[0], points.get(0)[1],
                    points.get(0)[0], points.get(0)[1]);
            for (float[] p : points) b.union(p[0], p[1]);
            b.inset(-width, -width);
            return b;
        }

        void offset(float dx, float dy) {
            for (float[] p : points) {
                p[0] = Math.max(0f, Math.min(PAGE_WIDTH, p[0] + dx));
                p[1] = Math.max(0f, Math.min(PAGE_HEIGHT, p[1] + dy));
            }
        }

        boolean near(float x, float y, float r2) {
            for (int i = 0; i < points.size(); i++) {
                float[] a = points.get(i);
                float[] b = i + 1 < points.size() ? points.get(i + 1) : a;
                if (segmentDistanceSquared(x, y, a[0], a[1], b[0], b[1]) <= r2) return true;
            }
            return false;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            JSONArray pts = new JSONArray();
            try {
                for (float[] p : points) {
                    pts.put(new JSONObject().put("x", p[0]).put("y", p[1]).put("p", p[2]));
                }
                json.put("pen", pen.name()).put("c", color).put("w", width).put("a", alpha)
                        .put("pts", pts);
            } catch (JSONException ignored) {
                // Only finite numbers and strings are written.
            }
            return json;
        }

        static Stroke fromJson(Object value) throws JSONException {
            JSONArray pts;
            Stroke stroke;
            if (value instanceof JSONArray) {
                pts = (JSONArray) value;
                stroke = new Stroke(Pen.BALLPOINT, 0, 3f, 255);
            } else {
                JSONObject json = (JSONObject) value;
                Pen pen;
                try {
                    pen = Pen.valueOf(json.optString("pen", "BALLPOINT"));
                } catch (IllegalArgumentException unknown) {
                    pen = Pen.BALLPOINT;
                }
                stroke = new Stroke(pen, json.optInt("c", 0), (float) json.optDouble("w", 4),
                        json.optInt("a", 255));
                pts = json.getJSONArray("pts");
            }
            for (int i = 0; i < pts.length(); i++) {
                JSONObject p = pts.getJSONObject(i);
                stroke.points.add(new float[]{(float) p.optDouble("x"), (float) p.optDouble("y"),
                        (float) p.optDouble("p", 0.5)});
            }
            return stroke;
        }
    }

    private static float segmentDistanceSquared(float x, float y, float x1, float y1,
            float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float lengthSquared = dx * dx + dy * dy;
        float t = lengthSquared == 0f ? 0f
                : Math.max(0f, Math.min(1f, ((x - x1) * dx + (y - y1) * dy) / lengthSquared));
        float px = x1 + t * dx - x;
        float py = y1 + t * dy - y;
        return px * px + py * py;
    }
}
