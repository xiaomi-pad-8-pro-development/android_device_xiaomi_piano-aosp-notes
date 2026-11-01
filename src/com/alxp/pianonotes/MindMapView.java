package com.alxp.pianonotes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Xiaomi Notes mind map: a central topic with branches laid out to its right.
 * Tap selects a topic, double tap edits it, drag pans and pinch zooms.
 */
final class MindMapView extends View {
    interface Listener {
        void onSelected(Node node);
        void onEdit(Node node);
        void onChanged();
    }

    static final class Node {
        String text;
        boolean collapsed;
        final List<Node> children = new ArrayList<>();
        Node parent;
        final RectF box = new RectF();
        float subtreeHeight;

        Node(String text) {
            this.text = text;
        }

        Node add(String childText) {
            Node child = new Node(childText);
            child.parent = this;
            children.add(child);
            return child;
        }
    }

    private static final int[] BRANCH_COLORS = {
            0xff6750a4, 0xff00897b, 0xffe65100, 0xff1e88e5, 0xffc2185b, 0xff7cb342};

    private final Listener listener;
    private final float density;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;
    private Node root = new Node("Central topic");
    private Node selected = root;
    private float panX;
    private float panY;
    private float zoom = 1f;
    private boolean centred;

    MindMapView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        text.setTextSize(16 * density);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onScroll(MotionEvent first, MotionEvent e, float dx, float dy) {
                panX -= dx;
                panY -= dy;
                invalidate();
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                Node hit = hit(root, toMapX(e.getX()), toMapY(e.getY()));
                if (hit != null) select(hit);
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                Node hit = hit(root, toMapX(e.getX()), toMapY(e.getY()));
                if (hit != null) {
                    select(hit);
                    listener.onEdit(hit);
                }
                return true;
            }
        });
        scaler = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float next = Math.max(0.3f, Math.min(3f, zoom * detector.getScaleFactor()));
                        float factor = next / zoom;
                        panX = detector.getFocusX() - (detector.getFocusX() - panX) * factor;
                        panY = detector.getFocusY() - (detector.getFocusY() - panY) * factor;
                        zoom = next;
                        invalidate();
                        return true;
                    }
                });
    }

    Node selected() {
        return selected;
    }

    Node root() {
        return root;
    }

    void select(Node node) {
        selected = node;
        listener.onSelected(node);
        invalidate();
    }

    void addChild() {
        selected.collapsed = false;
        Node child = selected.add("Topic");
        changed();
        select(child);
        listener.onEdit(child);
    }

    void addSibling() {
        if (selected.parent == null) {
            addChild();
            return;
        }
        Node sibling = new Node("Topic");
        sibling.parent = selected.parent;
        selected.parent.children.add(selected.parent.children.indexOf(selected) + 1, sibling);
        changed();
        select(sibling);
        listener.onEdit(sibling);
    }

    void deleteSelected() {
        if (selected.parent == null) return;
        Node parent = selected.parent;
        parent.children.remove(selected);
        changed();
        select(parent);
    }

    void toggleCollapsed() {
        if (selected.children.isEmpty()) return;
        selected.collapsed = !selected.collapsed;
        changed();
    }

    void setText(Node node, String value) {
        node.text = value.trim().isEmpty() ? "Topic" : value.trim();
        changed();
    }

    private void changed() {
        invalidate();
        listener.onChanged();
    }

    // --- Storage ------------------------------------------------------------

    String serialize() {
        try {
            return toJson(root).toString();
        } catch (JSONException impossible) {
            return "";
        }
    }

    void restore(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            root = fromJson(new JSONObject(json), null);
            selected = root;
        } catch (JSONException damaged) {
            // Keep the empty map rather than losing the note.
        }
        invalidate();
    }

    /** The map as an indented outline, kept as the note text for search and previews. */
    String outline() {
        StringBuilder out = new StringBuilder();
        outline(root, 0, out);
        return out.toString().trim();
    }

    private static void outline(Node node, int depth, StringBuilder out) {
        out.append("    ".repeat(depth)).append(depth == 0 ? "" : "• ").append(node.text).append('\n');
        for (Node child : node.children) outline(child, depth + 1, out);
    }

    private static JSONObject toJson(Node node) throws JSONException {
        JSONObject json = new JSONObject().put("t", node.text);
        if (node.collapsed) json.put("x", true);
        if (!node.children.isEmpty()) {
            JSONArray children = new JSONArray();
            for (Node child : node.children) children.put(toJson(child));
            json.put("c", children);
        }
        return json;
    }

    private static Node fromJson(JSONObject json, Node parent) throws JSONException {
        Node node = new Node(json.optString("t", "Topic"));
        node.parent = parent;
        node.collapsed = json.optBoolean("x", false);
        JSONArray children = json.optJSONArray("c");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                node.children.add(fromJson(children.getJSONObject(i), node));
            }
        }
        return node;
    }

    // --- Layout and drawing -------------------------------------------------

    private float gapX() {
        return 56 * density;
    }

    private float gapY() {
        return 14 * density;
    }

    private void measure(Node node) {
        float pad = 14 * density;
        float width = Math.min(text.measureText(node.text), 260 * density) + pad * 2;
        float height = text.getTextSize() + pad * 1.6f;
        node.box.set(0, 0, width, height);
        float children = 0;
        if (!node.collapsed) {
            for (Node child : node.children) {
                measure(child);
                children += child.subtreeHeight;
            }
            children += gapY() * Math.max(0, node.children.size() - 1);
        }
        node.subtreeHeight = Math.max(height, children);
    }

    private void place(Node node, float left, float centreY) {
        node.box.offsetTo(left, centreY - node.box.height() / 2);
        if (node.collapsed) return;
        float total = 0;
        for (Node child : node.children) total += child.subtreeHeight;
        total += gapY() * Math.max(0, node.children.size() - 1);
        float top = centreY - total / 2;
        for (Node child : node.children) {
            place(child, node.box.right + gapX(), top + child.subtreeHeight / 2);
            top += child.subtreeHeight + gapY();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        measure(root);
        place(root, 0, 0);
        if (!centred && getWidth() > 0) {
            centred = true;
            panX = 32 * density;
            panY = getHeight() / 2f;
        }
        canvas.save();
        canvas.translate(panX, panY);
        canvas.scale(zoom, zoom);
        drawLinks(canvas, root, 0);
        drawNodes(canvas, root, 0, 0);
        canvas.restore();
    }

    private void drawLinks(Canvas canvas, Node node, int depth) {
        if (node.collapsed) return;
        for (int i = 0; i < node.children.size(); i++) {
            Node child = node.children.get(i);
            line.setColor(BRANCH_COLORS[(depth == 0 ? i : branchOf(child)) % BRANCH_COLORS.length]);
            Path path = new Path();
            float startX = node.box.right;
            float startY = node.box.centerY();
            float endX = child.box.left;
            float endY = child.box.centerY();
            float mid = (startX + endX) / 2;
            path.moveTo(startX, startY);
            path.cubicTo(mid, startY, mid, endY, endX, endY);
            canvas.drawPath(path, line);
            drawLinks(canvas, child, depth + 1);
        }
    }

    private void drawNodes(Canvas canvas, Node node, int depth, int branch) {
        float radius = node.box.height() / 2;
        int color = BRANCH_COLORS[branch % BRANCH_COLORS.length];
        if (depth == 0) {
            fill.setColor(color);
            text.setColor(0xffffffff);
        } else {
            fill.setColor((color & 0x00ffffff) | 0x26000000);
            text.setColor(0xff1d1b20);
        }
        canvas.drawRoundRect(node.box, radius, radius, fill);
        if (node == selected) {
            line.setColor(0xff1d1b20);
            canvas.drawRoundRect(node.box, radius, radius, line);
        }
        float pad = 14 * density;
        String label = android.text.TextUtils.ellipsize(node.text, new android.text.TextPaint(text),
                node.box.width() - pad * 2, android.text.TextUtils.TruncateAt.END).toString();
        canvas.drawText(label, node.box.left + pad,
                node.box.centerY() - (text.descent() + text.ascent()) / 2, text);
        if (node.collapsed && !node.children.isEmpty()) {
            fill.setColor(color);
            canvas.drawCircle(node.box.right + 8 * density, node.box.centerY(), 6 * density, fill);
        }
        if (node.collapsed) return;
        for (int i = 0; i < node.children.size(); i++) {
            drawNodes(canvas, node.children.get(i), depth + 1, depth == 0 ? i : branch);
        }
    }

    private int branchOf(Node node) {
        while (node.parent != null && node.parent.parent != null) node = node.parent;
        return node.parent == null ? 0 : node.parent.children.indexOf(node);
    }

    private Node hit(Node node, float x, float y) {
        if (node.box.contains(x, y)) return node;
        if (node.collapsed) return null;
        for (Node child : node.children) {
            Node found = hit(child, x, y);
            if (found != null) return found;
        }
        return null;
    }

    private float toMapX(float x) {
        return (x - panX) / zoom;
    }

    private float toMapY(float y) {
        return (y - panY) / zoom;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaler.onTouchEvent(event);
        if (!scaler.isInProgress()) gestures.onTouchEvent(event);
        return true;
    }
}
