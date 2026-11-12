package com.alxp.pianonotes;

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
    private static final int[] PEN_ICONS = {R.drawable.ic_tool_pen, R.drawable.ic_tool_fountain, R.drawable.ic_tool_pencil, R.drawable.ic_tool_marker, R.drawable.ic_tool_highlight, R.drawable.ic_tool_brush, R.drawable.ic_tool_crayon};

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
        TextView pageIndicator = new TextView(activity);
        pageIndicator.setTextColor(bar.color(
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY));
        pageIndicator.setTextSize(14);
        pageIndicator.setGravity(Gravity.CENTER);
        pageIndicator.setPadding(bar.dp(12), bar.dp(8), bar.dp(12), bar.dp(8));
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(bar.color(com.google.android.material.R.attr.colorSurfaceContainerHigh,
                Color.LTGRAY));
        badge.setCornerRadius(bar.dp(16));
        pageIndicator.setBackground(badge);
        pageIndicator.setMinHeight(bar.dp(48));
        pageIndicator.setContentDescription("Choose handwriting page");
        pageIndicator.setOnClickListener(v -> bar.showPages());
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.END);
        badgeLp.setMargins(bar.dp(12), bar.dp(12), bar.dp(12), 0);
        frame.addView(pageIndicator, badgeLp);
        ink.setPageStateListener(() -> pageIndicator.setText(
                "Page " + (ink.currentPage() + 1) + " / " + ink.pageCount()));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = bar.dp(16);
        frame.addView(card, lp);
        bar.selectPen(bar.pen);
        ink.setPenActions(bar::showPenSettings, () -> bar.showColorPicker(bar::setPenColor));
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
        handle.setGravity(Gravity.CENTER);
        handle.setMinWidth(dp(48));
        handle.setMinHeight(dp(48));
        handle.setContentDescription("Move the pen bar");
        handle.setOnTouchListener(new Dragger(card));
        row.addView(handle);

        InkView.Pen[] pens = InkView.Pen.values();
        for (int i = 0; i < pens.length; i++) {
            InkView.Pen p = pens[i];
            MaterialButton button = tool(row, PEN_ICONS[i], PEN_LABELS[i]);
            button.setOnClickListener(v -> {
                if (pen == p && ink.mode() == InkView.Mode.DRAW) showPenSettings();
                else selectPen(p);
            });
            penButtons.put(p, button);
        }
        eraserButton = tool(row, R.drawable.ic_tool_eraser, "Eraser");
        eraserButton.setOnClickListener(v -> {
            boolean erasing = ink.mode() == InkView.Mode.STROKE_ERASER
                    || ink.mode() == InkView.Mode.PIXEL_ERASER;
            if (erasing) showEraserSettings();
            else setMode(prefs.getBoolean("pixel_eraser", false)
                    ? InkView.Mode.PIXEL_ERASER : InkView.Mode.STROKE_ERASER);
        });
        lassoButton = tool(row, R.drawable.ic_tool_lasso, "Lasso");
        lassoButton.setOnClickListener(v -> {
            if (ink.mode() == InkView.Mode.LASSO && ink.hasSelection()) showLassoActions();
            else setMode(InkView.Mode.LASSO);
        });
        rulerButton = tool(row, R.drawable.ic_tool_ruler, "Ruler");
        rulerButton.setTooltipText("Ruler: drag with one finger, rotate with two fingers");
        ink.setRuler(prefs.getBoolean("ruler_visible", false));
        rulerButton.setOnClickListener(v -> {
            ink.setRuler(!ink.ruler());
            prefs.edit().putBoolean("ruler_visible", ink.ruler()).apply();
            refresh();
        });
        tool(row, R.drawable.ic_tool_undo, "Undo").setOnClickListener(v -> ink.undo());
        tool(row, R.drawable.ic_tool_redo, "Redo").setOnClickListener(v -> ink.redo());

        colorDot = new View(activity);
        colorDot.setContentDescription("Colour");
        colorDot.setOnClickListener(v -> showColorPicker(this::setPenColor));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        dotLp.setMargins(dp(8), 0, dp(8), 0);
        row.addView(colorDot, dotLp);
        ink.setInputMode(prefs.getInt("input_mode",
                prefs.getBoolean("stylus_only", false) ? 1 : 2));
        MaterialButton inputMode = tool(row, R.drawable.ic_tool_pen, "Pen input settings");
        inputMode.setOnClickListener(v -> new MaterialAlertDialogBuilder(activity)
                .setTitle("Drawing input")
                .setSingleChoiceItems(new String[]{"Pen and finger", "Pen only", "Automatic pen detection"},
                        prefs.getInt("input_mode", prefs.getBoolean("stylus_only", false) ? 1 : 2),
                        (dialog, which) -> {
                            prefs.edit().putInt("input_mode", which).apply();
                            ink.setInputMode(which);
                            dialog.dismiss();
                        })
                .setNegativeButton("Cancel", null)
                .show());
        tool(row, R.drawable.ic_fit_page_24, "Fit page").setOnClickListener(v -> ink.fitPage());
        tool(row, R.drawable.ic_notes_24, "Pages").setOnClickListener(v -> showPages());
        ink.setPaperPattern(prefs.getInt("paper_pattern", 0));
        tool(row, R.drawable.ic_notes_24, "Paper guides").setOnClickListener(v ->
                new MaterialAlertDialogBuilder(activity)
                        .setTitle("Paper guides")
                        .setSingleChoiceItems(new String[]{"Plain", "Ruled", "Squared", "Dotted"},
                                prefs.getInt("paper_pattern", 0), (dialog, which) -> {
                                    prefs.edit().putInt("paper_pattern", which).apply();
                                    ink.setPaperPattern(which);
                                    dialog.dismiss();
                                })
                        .setNegativeButton("Cancel", null).show());
        ink.setEraserRadius(prefs.getFloat("eraser_radius", 22f));
        return card;
    }

    private void showPages() {
        String[] items = new String[ink.pageCount() + 1];
        for (int i = 0; i < ink.pageCount(); i++)
            items[i] = "Page " + (i + 1) + (i == ink.currentPage() ? " · Current" : "");
        items[items.length - 1] = "Add page";
        new MaterialAlertDialogBuilder(activity).setTitle("Pages")
                .setItems(items, (dialog, which) -> {
                    if (which == ink.pageCount()) {
                        if (ink.pageCount() >= InkDocument.MAX_PAGES)
                            android.widget.Toast.makeText(activity, "Page limit reached",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        else ink.addPage();
                    } else ink.showPage(which);
                }).setNeutralButton("Page actions", (dialog, which) -> showPageActions())
                .setNegativeButton("Close", null).show();
    }

    private void showPageActions() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Page " + (ink.currentPage() + 1))
                .setItems(new String[]{"Duplicate page", "Delete page"}, (dialog, which) -> {
                    if (which == 0) {
                        if (ink.pageCount() >= InkDocument.MAX_PAGES) {
                            android.widget.Toast.makeText(activity, "Page limit reached",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        } else ink.duplicatePage();
                    } else new MaterialAlertDialogBuilder(activity)
                            .setTitle("Delete this page?")
                            .setMessage("The page and its handwriting will be removed. You can undo this action.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete", (confirm, button) -> ink.deletePage())
                            .show();
                }).setNegativeButton("Cancel", null).show();
    }

    private MaterialButton tool(LinearLayout row, int icon, String description) {
        MaterialButton button = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialIconButtonStyle);
        button.setIconResource(icon);
        button.setIconSize(dp(24));
        button.setIconPadding(0);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setTextSize(18);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(0, 0, 0, 0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, lp);
        return button;
    }

    // --- Tools ----------------------------------------------------------------

    private void selectPen(InkView.Pen p) {
        pen = p;
        prefs.edit().putString("pen", p.name()).apply();
        ink.setPen(p, penColor(), penWidth(), penAlpha());
        ink.setPressureEnabled(prefs.getBoolean(p.name() + ".pressure", true));
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
        android.widget.CheckBox pressure = new android.widget.CheckBox(activity);
        pressure.setText("Pressure-sensitive width");
        pressure.setChecked(prefs.getBoolean(pen.name() + ".pressure", true));
        boolean supportsPressure = pen == InkView.Pen.BALLPOINT || pen == InkView.Pen.FOUNTAIN;
        if (supportsPressure) content.addView(pressure);
        Slider thickness = slider(content, "Stroke thickness", 1f, 24f, penWidth());
        Slider opacity = slider(content, "Stroke opacity", 10f, 100f, penAlpha() * 100f / 255f);
        View strokePreview = new View(activity) {
            private final android.graphics.Paint paint = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                paint.setColor(penColor());
                paint.setAlpha(Math.round(opacity.getValue() * 255f / 100f));
                paint.setStrokeWidth(dp(1) * thickness.getValue());
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                canvas.drawLine(dp(20), getHeight() / 2f,
                        getWidth() - dp(20), getHeight() / 2f, paint);
            }
        };
        strokePreview.setContentDescription("Stroke thickness and opacity preview");
        content.addView(strokePreview, new LinearLayout.LayoutParams(-1, dp(64)));
        thickness.addOnChangeListener((slider, value, fromUser) -> strokePreview.invalidate());
        opacity.addOnChangeListener((slider, value, fromUser) -> strokePreview.invalidate());
        new MaterialAlertDialogBuilder(activity)
                .setTitle(PEN_LABELS[pen.ordinal()])
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Done", (d, w) -> {
                    prefs.edit().putBoolean(pen.name() + ".pressure", pressure.isChecked())
                            .putFloat(pen.name() + ".width", thickness.getValue())
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
        slider.setContentDescription(label);
        title.setText(label + " · " + Math.round(slider.getValue()));
        slider.addOnChangeListener((control, current, fromUser) ->
                title.setText(label + " · " + Math.round(current)));
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
                    View parent = (View) bar.getParent();
                    float x = event.getRawX() - startX;
                    float y = event.getRawY() - startY;
                    bar.setTranslationX(Math.max(-bar.getLeft(),
                            Math.min(parent.getWidth() - bar.getRight(), x)));
                    bar.setTranslationY(Math.max(-bar.getTop(),
                            Math.min(parent.getHeight() - bar.getBottom(), y)));
                    return true;
                default:
                    return true;
            }
        }
    }
}
