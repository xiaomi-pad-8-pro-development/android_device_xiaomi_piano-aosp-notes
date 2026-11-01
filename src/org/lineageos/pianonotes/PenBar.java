package org.lineageos.pianonotes;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.util.EnumMap;
import java.util.Map;

/**
 * The floating pen bar of Xiaomi Notes in Material 3: the seven pens, the
 * erasers, lasso, ruler, undo/redo and the colour picker. Tap a selected
 * tool again for its settings; drag the handle to move the bar.
 */
final class PenBar {
    private static final int[] PALETTE = {
            0xff1f1f1f, 0xff5f6368, 0xffffffff, 0xffd93025, 0xfff29900, 0xfffbbc04,
            0xff188038, 0xff12b5cb, 0xff1a73e8, 0xff7e57c2, 0xffe52592, 0xff795548,
    };
    private static final String[] PEN_LABELS = {
            "Ballpoint pen", "Fountain pen", "Pencil", "Marker", "Highlighter",
            "Watercolor brush", "Crayon",
    };
    private static final String[] PEN_GLYPHS = {"✒", "🖋", "✏", "🖊", "▬", "🖌", "🖍"};

    private final Activity activity;
    private final InkView ink;
    private final SharedPreferences prefs;
    private final Map<InkView.Pen, MaterialButton> penButtons = new EnumMap<>(InkView.Pen.class);
    private MaterialButton eraserButton;
    private MaterialButton lassoButton;
    private MaterialButton rulerButton;
    private View colorDot;
    private InkView.Pen pen;

    private PenBar(Activity activity, InkView ink) {
        this.activity = activity;
        this.ink = ink;
        prefs = activity.getSharedPreferences("pens", Activity.MODE_PRIVATE);
        pen = InkView.Pen.valueOf(prefs.getString("pen", InkView.Pen.BALLPOINT.name()));
    }

    /** Puts the page and a draggable pen bar over it into one frame. */
    static FrameLayout wrap(Activity activity, InkView ink, View page) {
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        PenBar bar = new PenBar(activity, ink);
        View card = bar.build();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = bar.dp(16);
        frame.addView(card, lp);
        bar.selectPen(bar.pen);
        return frame;
    }

    private View build() {
        MaterialCardView card = new MaterialCardView(activity);
        card.setRadius(dp(28));
        card.setCardElevation(dp(6));
        card.setCardBackgroundColor(color(
                com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.WHITE));
        HorizontalScrollView scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        scroller.addView(row);
        card.addView(scroller);

        TextView handle = new TextView(activity);
        handle.setText("⋮⋮");
        handle.setTextSize(18);
        handle.setPadding(dp(8), 0, dp(8), 0);
        handle.setContentDescription("Move the pen bar");
        handle.setOnTouchListener(new Dragger(card));
        row.addView(handle);

        InkView.Pen[] pens = InkView.Pen.values();
        for (int i = 0; i < pens.length; i++) {
            InkView.Pen p = pens[i];
            MaterialButton button = tool(row, PEN_GLYPHS[i], PEN_LABELS[i]);
            button.setOnClickListener(v -> {
                if (pen == p && ink.mode() == InkView.Mode.DRAW) showPenSettings();
                else selectPen(p);
            });
            penButtons.put(p, button);
        }
        eraserButton = tool(row, "⌫", "Eraser");
        eraserButton.setOnClickListener(v -> {
            boolean erasing = ink.mode() == InkView.Mode.STROKE_ERASER
                    || ink.mode() == InkView.Mode.PIXEL_ERASER;
            if (erasing) showEraserSettings();
            else setMode(prefs.getBoolean("pixel_eraser", false)
                    ? InkView.Mode.PIXEL_ERASER : InkView.Mode.STROKE_ERASER);
        });
        lassoButton = tool(row, "⬚", "Lasso");
        lassoButton.setOnClickListener(v -> {
            if (ink.mode() == InkView.Mode.LASSO && ink.hasSelection()) showLassoActions();
            else setMode(InkView.Mode.LASSO);
        });
        rulerButton = tool(row, "📏", "Ruler");
        rulerButton.setOnClickListener(v -> {
            ink.setRuler(!ink.ruler());
            refresh();
        });
        tool(row, "↶", "Undo").setOnClickListener(v -> ink.undo());
        tool(row, "↷", "Redo").setOnClickListener(v -> ink.redo());

        colorDot = new View(activity);
        colorDot.setContentDescription("Colour");
        colorDot.setOnClickListener(v -> showColorPicker(this::setPenColor));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        dotLp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(colorDot, dotLp);
        ink.setEraserRadius(prefs.getFloat("eraser_radius", 22f));
        return card;
    }

    private MaterialButton tool(LinearLayout row, String glyph, String description) {
        MaterialButton button = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        button.setText(glyph);
        button.setTextSize(18);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, lp);
        return button;
    }

    // --- Tools ----------------------------------------------------------------

    private void selectPen(InkView.Pen p) {
        pen = p;
        prefs.edit().putString("pen", p.name()).apply();
        ink.setPen(p, penColor(), penWidth(), penAlpha());
        refresh();
    }

    private void setMode(InkView.Mode mode) {
        ink.setMode(mode);
        refresh();
    }

    private void refresh() {
        int selected = color(com.google.android.material.R.attr.colorSecondaryContainer,
                0xffe8def8);
        for (Map.Entry<InkView.Pen, MaterialButton> entry : penButtons.entrySet()) {
            boolean on = ink.mode() == InkView.Mode.DRAW && entry.getKey() == pen;
            entry.getValue().setBackgroundTintList(ColorStateList.valueOf(
                    on ? selected : Color.TRANSPARENT));
        }
        boolean erasing = ink.mode() == InkView.Mode.STROKE_ERASER
                || ink.mode() == InkView.Mode.PIXEL_ERASER;
        eraserButton.setBackgroundTintList(ColorStateList.valueOf(
                erasing ? selected : Color.TRANSPARENT));
        lassoButton.setBackgroundTintList(ColorStateList.valueOf(
                ink.mode() == InkView.Mode.LASSO ? selected : Color.TRANSPARENT));
        rulerButton.setBackgroundTintList(ColorStateList.valueOf(
                ink.ruler() ? selected : Color.TRANSPARENT));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(penColor());
        dot.setStroke(dp(2), color(com.google.android.material.R.attr.colorOutline, Color.GRAY));
        colorDot.setBackground(dot);
    }

    private int penColor() {
        int fallback = pen == InkView.Pen.HIGHLIGHTER ? 0xfffbbc04
                : color(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY);
        return prefs.getInt(pen.name() + ".color", fallback);
    }

    private float penWidth() {
        float fallback = pen == InkView.Pen.PENCIL ? 3f : pen == InkView.Pen.FOUNTAIN ? 5f : 4f;
        return prefs.getFloat(pen.name() + ".width", fallback);
    }

    private int penAlpha() {
        return prefs.getInt(pen.name() + ".alpha", 255);
    }

    private void setPenColor(int value) {
        prefs.edit().putInt(pen.name() + ".color", value).apply();
        selectPen(pen);
    }

    private void showPenSettings() {
        LinearLayout content = column();
        Slider thickness = slider(content, "Stroke thickness", 1f, 24f, penWidth());
        Slider opacity = slider(content, "Stroke opacity", 10f, 100f, penAlpha() * 100f / 255f);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(PEN_LABELS[pen.ordinal()])
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    prefs.edit().putFloat(pen.name() + ".width", thickness.getValue())
                            .putInt(pen.name() + ".alpha",
                                    Math.round(opacity.getValue() * 255f / 100f)).apply();
                    selectPen(pen);
                })
                .show();
    }

    private void showEraserSettings() {
        LinearLayout content = column();
        boolean pixel = prefs.getBoolean("pixel_eraser", false);
        MaterialButton kind = new MaterialButton(activity);
        kind.setText(pixel ? "Pixel eraser" : "Stroke eraser");
        kind.setOnClickListener(v -> kind.setText(kind.getText().toString().startsWith("Pixel")
                ? "Stroke eraser" : "Pixel eraser"));
        content.addView(kind);
        Slider size = slider(content, "Eraser size", 8f, 80f,
                prefs.getFloat("eraser_radius", 22f));
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Eraser")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    boolean usePixel = kind.getText().toString().startsWith("Pixel");
                    prefs.edit().putBoolean("pixel_eraser", usePixel)
                            .putFloat("eraser_radius", size.getValue()).apply();
                    ink.setEraserRadius(size.getValue());
                    setMode(usePixel ? InkView.Mode.PIXEL_ERASER : InkView.Mode.STROKE_ERASER);
                })
                .show();
    }

    private void showLassoActions() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Selection")
                .setItems(new String[]{"Change colour", "Delete"}, (d, which) -> {
                    if (which == 0) showColorPicker(ink::recolorSelection);
                    else ink.deleteSelection();
                })
                .show();
    }

    // --- Colour picker --------------------------------------------------------

    /** Palette, custom hue/saturation/lightness/opacity, and the eyedropper. */
    private void showColorPicker(java.util.function.IntConsumer onPicked) {
        LinearLayout content = column();
        View preview = new View(activity);
        int[] current = {penColor()};
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(40)));

        GridLayout palette = new GridLayout(activity);
        palette.setColumnCount(6);
        content.addView(palette);
        float[] hsl = new float[3];
        androidx.core.graphics.ColorUtils.colorToHSL(current[0], hsl);
        Slider hue = slider(content, "Hue", 0f, 360f, hsl[0]);
        Slider saturation = slider(content, "Saturation", 0f, 100f, hsl[1] * 100f);
        Slider lightness = slider(content, "Lightness", 0f, 100f, hsl[2] * 100f);
        Slider opacity = slider(content, "Opacity", 10f, 100f,
                Color.alpha(current[0]) * 100f / 255f);
        Runnable fromSliders = () -> {
            int rgb = androidx.core.graphics.ColorUtils.HSLToColor(new float[]{
                    hue.getValue(), saturation.getValue() / 100f, lightness.getValue() / 100f});
            current[0] = (Math.round(opacity.getValue() * 255f / 100f) << 24) | (rgb & 0xffffff);
            paintPreview(preview, current[0]);
        };
        for (Slider s : new Slider[]{hue, saturation, lightness, opacity}) {
            s.addOnChangeListener((slider, value, fromUser) -> {
                if (fromUser) fromSliders.run();
            });
        }
        for (int swatch : PALETTE) {
            View cell = new View(activity);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(swatch);
            dot.setStroke(dp(1), color(com.google.android.material.R.attr.colorOutlineVariant,
                    Color.LTGRAY));
            cell.setBackground(dot);
            cell.setOnClickListener(v -> {
                float[] h = new float[3];
                androidx.core.graphics.ColorUtils.colorToHSL(swatch, h);
                hue.setValue(h[0]);
                saturation.setValue(h[1] * 100f);
                lightness.setValue(h[2] * 100f);
                fromSliders.run();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(36);
            lp.height = dp(36);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            palette.addView(cell, lp);
        }
        paintPreview(preview, current[0]);
        androidx.appcompat.app.AlertDialog[] dialog = new androidx.appcompat.app.AlertDialog[1];
        MaterialButton dropper = new MaterialButton(activity);
        dropper.setText("Eyedropper");
        dropper.setOnClickListener(v -> {
            dialog[0].dismiss();
            ink.pickColor(picked -> onPicked.accept(picked));
        });
        content.addView(dropper);
        dialog[0] = new MaterialAlertDialogBuilder(activity)
                .setTitle("Colour")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> onPicked.accept(current[0]))
                .show();
    }

    private void paintPreview(View preview, int value) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(20));
        shape.setColor(value);
        preview.setBackground(shape);
    }

    // --- Small helpers --------------------------------------------------------

    private LinearLayout column() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), 0);
        return content;
    }

    private Slider slider(LinearLayout parent, String label, float from, float to, float value) {
        TextView title = new TextView(activity);
        title.setText(label);
        title.setPadding(0, dp(12), 0, 0);
        parent.addView(title);
        Slider slider = new Slider(activity);
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setValue(Math.max(from, Math.min(to, value)));
        parent.addView(slider);
        return slider;
    }

    private int color(int attribute, int fallback) {
        return MaterialColors.getColor(activity, attribute, fallback);
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Drags the bar around inside its frame. */
    private static final class Dragger implements View.OnTouchListener {
        private final View bar;
        private float startX;
        private float startY;

        Dragger(View bar) {
            this.bar = bar;
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX() - bar.getTranslationX();
                    startY = event.getRawY() - bar.getTranslationY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    bar.setTranslationX(event.getRawX() - startX);
                    bar.setTranslationY(event.getRawY() - startY);
                    return true;
                default:
                    return true;
            }
        }
    }
}
