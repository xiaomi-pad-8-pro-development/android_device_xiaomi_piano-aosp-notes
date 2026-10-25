package org.lineageos.pianonotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class NotesActivity extends Activity {
    private static final int REQUEST_EXPORT = 10;
    private static final int REQUEST_IMPORT = 11;
    private static final String STATE_EDITOR = "editor";
    private static final String STATE_NOTE_ID = "note_id";
    private static final String STATE_DRAWING = "drawing";
    private static final String STATE_LIST_SCROLL = "list_scroll";
    private NoteStore store;
    private LinearLayout root;
    private long noteId = -1;
    private EditText title;
    private EditText body;
    private InkView ink;
    private boolean drawing;
    private boolean transferRunning;
    private int listScrollY;
    private ScrollView noteListScroll;
    private Handler autosaveHandler;
    private String lastSavedTitle = "";
    private String lastSavedBody = "";
    private final Runnable textAutosave = () -> runTextAutosave();
    private final Runnable inkAutosave = () -> runInkAutosave();

    private void runTextAutosave() {
        if (title == null || body == null || drawing) return;
        String currentTitle = title.getText().toString();
        String currentBody = body.getText().toString();
        if (currentTitle.equals(lastSavedTitle) && currentBody.equals(lastSavedBody)) return;
        try {
            saveCurrent();
            lastSavedTitle = currentTitle;
            lastSavedBody = currentBody;
        } catch (RuntimeException failure) {
            Toast.makeText(this, "Could not autosave note: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void runInkAutosave() {
        if (title == null || !drawing || ink == null) return;
        try {
            saveCurrent();
        } catch (RuntimeException failure) {
            Toast.makeText(this, "Could not autosave ink note: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void scheduleInkAutosave() {
        if (autosaveHandler == null) return;
        autosaveHandler.removeCallbacks(inkAutosave);
        autosaveHandler.postDelayed(inkAutosave, 700);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new NoteStore(this);
        autosaveHandler = new Handler(Looper.getMainLooper());
        if (state != null) listScrollY = state.getInt(STATE_LIST_SCROLL, 0);
        if (state != null && state.getBoolean(STATE_EDITOR, false)) {
            long restoredId = state.getLong(STATE_NOTE_ID, -1);
            NoteStore.Note note = restoredId < 0 ? null : store.get(restoredId);
            if (restoredId >= 0 && note == null) {
                showList();
                return;
            }
            boolean restoredDrawing = state.getBoolean(STATE_DRAWING, false);
            if (restoredDrawing) editInk(note); else edit(note);
        } else {
            showList();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        // Persist editor contents before putting only a small note ID in the
        // Activity bundle. Ink JSON can grow beyond Binder's saved-state limit.
        saveCurrent();
        state.putBoolean(STATE_EDITOR, title != null);
        state.putLong(STATE_NOTE_ID, noteId);
        state.putBoolean(STATE_DRAWING, drawing);
        state.putInt(STATE_LIST_SCROLL, noteListScroll == null
                ? listScrollY : noteListScroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    private void base(String heading) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));
        root.setBackgroundColor(Color.rgb(250, 249, 246));
        setContentView(root);
        TextView bar = new TextView(this);
        bar.setText(heading);
        bar.setTextSize(24);
        bar.setTextColor(Color.rgb(30, 30, 30));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));
    }

    private void showList() {
        cancelEditorAutosaves();
        noteId = -1;
        title = null;
        body = null;
        ink = null;
        drawing = false;
        base("Notes");
        LinearLayout actions = new LinearLayout(this);
        Button text = button("New text note");
        text.setOnClickListener(v -> {
            if (requireTransferIdle()) edit(null);
        });
        Button draw = button("New ink note");
        draw.setOnClickListener(v -> {
            if (requireTransferIdle()) editInk(null);
        });
        actions.addView(text, weight());
        actions.addView(draw, weight());
        root.addView(actions);

        LinearLayout transferActions = new LinearLayout(this);
        Button export = button("Export backup");
        export.setOnClickListener(v -> chooseExportDestination());
        Button importButton = button("Import backup");
        importButton.setOnClickListener(v -> confirmImport());
        transferActions.addView(export, weight());
        transferActions.addView(importButton, weight());
        root.addView(transferActions);

        Button backupInfo = button("Automatic backup info");
        backupInfo.setOnClickListener(v -> showBackupInfo());
        root.addView(backupInfo, new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView scroll = new ScrollView(this);
        noteListScroll = scroll;
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (NoteStore.NoteSummary note : store.listSummaries()) {
            TextView row = new TextView(this);
            String preview = note.preview.replace('\n', ' ');
            if (preview.length() > 90) preview = preview.substring(0, 90) + "…";
            row.setText((note.title.isEmpty() ? "Untitled" : note.title)
                    + "\n" + preview + "\n" + android.text.format.DateFormat
                    .getMediumDateFormat(this).format(note.updated));
            row.setTextSize(16);
            row.setTextColor(Color.DKGRAY);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            rows.addView(row, lp);
            row.setOnClickListener(v -> {
                if (!requireTransferIdle()) return;
                NoteStore.Note selected = store.get(note.id);
                if (selected != null) {
                    if (selected.ink) editInk(selected); else edit(selected);
                }
            });
        }
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.post(() -> scroll.scrollTo(0, listScrollY));
    }

    private void rememberListPosition() {
        if (noteListScroll != null) listScrollY = noteListScroll.getScrollY();
    }

    private void chooseExportDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "piano-notes-backup.zip");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void confirmImport() {
        new AlertDialog.Builder(this)
                .setTitle("Replace local notes?")
                .setMessage("Import replaces all notes currently stored on this device. "
                        + "Export a backup first if you want to keep them.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose backup", (dialog, which) -> chooseImportSource())
                .show();
    }

    private void showBackupInfo() {
        new AlertDialog.Builder(this)
                .setTitle("Android backup and restore")
                .setMessage("Android Auto Backup can include this app's notes database when "
                        + "device backup is enabled and a compatible backup service is active. "
                        + "Android controls when backups run, so this screen cannot confirm "
                        + "that a backup has completed. On a replacement phone, install the "
                        + "same app package signed with the same certificate before restoring. "
                        + "For a portable copy, use Export backup; large libraries may exceed "
                        + "the cloud backup quota.")
                .setNegativeButton("Close", null)
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_PRIVACY_SETTINGS));
                    } catch (android.content.ActivityNotFoundException missingPrivacySettings) {
                        try {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        } catch (android.content.ActivityNotFoundException missingSettings) {
                            Toast.makeText(this, "System Settings could not be opened.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void chooseImportSource() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) exportTo(uri);
        else if (requestCode == REQUEST_IMPORT) importFrom(uri);
    }

    private void exportTo(Uri uri) {
        if (transferRunning) return;
        transferRunning = true;
        new Thread(() -> {
            String message;
            try {
                NotesTransfer.write(this, uri, store);
                message = "Notes backup exported.";
            } catch (Exception failure) {
                message = "Could not export backup: " + failure.getMessage();
            }
            String result = message;
            runOnUiThread(() -> finishTransfer(result, false));
        }, "notes-export").start();
    }

    private void importFrom(Uri uri) {
        if (transferRunning) return;
        transferRunning = true;
        new Thread(() -> {
            String message;
            try {
                int imported = NotesTransfer.replace(this, uri, store);
                message = "Imported " + imported + " notes.";
            } catch (Exception failure) {
                message = "Could not import backup: " + failure.getMessage();
            }
            String result = message;
            runOnUiThread(() -> finishTransfer(result, true));
        }, "notes-import").start();
    }

    private void finishTransfer(String message, boolean refreshList) {
        transferRunning = false;
        if (isFinishing() || isDestroyed()) {
            if (store != null) {
                store.close();
                store = null;
            }
            return;
        }
        if (refreshList) showList();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void edit(NoteStore.Note note) {
        rememberListPosition();
        drawing = false;
        noteId = note == null ? -1 : note.id;
        base("Text note");
        title = field("Title", note == null ? "" : note.title, false);
        body = field("Write something…", note == null ? "" : note.body, true);
        root.addView(title);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        addEditorActions(false);
        lastSavedTitle = title.getText().toString();
        lastSavedBody = body.getText().toString();
    }

    private void editInk(NoteStore.Note note) {
        rememberListPosition();
        drawing = true;
        noteId = note == null ? -1 : note.id;
        base("Ink note");
        title = field("Title", note == null ? "" : note.title, false);
        root.addView(title);
        ink = new InkView(this, this::scheduleInkAutosave);
        if (note != null) ink.restore(note.inkJson);
        LinearLayout.LayoutParams inkLp = new LinearLayout.LayoutParams(-1, 0, 1);
        inkLp.topMargin = dp(10);
        inkLp.bottomMargin = dp(10);
        root.addView(ink, inkLp);
        addEditorActions(true);
    }

    private void addEditorActions(boolean inkMode) {
        LinearLayout actions = new LinearLayout(this);
        Button back = button("Back");
        back.setOnClickListener(v -> saveAndReturn());
        actions.addView(back, weight());
        if (inkMode) {
            Button undo = button("Undo");
            undo.setOnClickListener(v -> ink.undo());
            actions.addView(undo, weight());
            Button clear = button("Clear");
            clear.setOnClickListener(v -> ink.clear());
            actions.addView(clear, weight());
        }
        Button save = button("Save");
        save.setOnClickListener(v -> {
            saveCurrent();
            showList();
        });
        actions.addView(save, weight());
        if (noteId >= 0) {
            Button delete = button("Delete");
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete this note?")
                    .setMessage("This note will be permanently deleted from this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> {
                        rememberListPosition();
                        store.delete(noteId);
                        showList();
                    })
                    .show());
            actions.addView(delete, weight());
        }
        root.addView(actions);
    }

    private EditText field(String hint, String value, boolean multiline) {
        EditText view = new EditText(this);
        view.setSingleLine(!multiline);
        view.setHint(hint);
        view.setText(value);
        view.setTextSize(multiline ? 18 : 20);
        view.setTextColor(Color.rgb(25, 25, 25));
        view.setInputType(multiline
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        view.setGravity(Gravity.TOP | Gravity.START);
        view.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            if (autosaveHandler != null) {
                if (drawing) {
                    scheduleInkAutosave();
                } else {
                    autosaveHandler.removeCallbacks(textAutosave);
                    autosaveHandler.postDelayed(textAutosave, 700);
                }
            }
            return null;
        }});
        return view;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private void cancelEditorAutosaves() {
        if (autosaveHandler != null) {
            autosaveHandler.removeCallbacks(textAutosave);
            autosaveHandler.removeCallbacks(inkAutosave);
        }
    }

    private boolean requireTransferIdle() {
        if (!transferRunning) return true;
        Toast.makeText(this, "Please wait for the backup operation to finish.",
                Toast.LENGTH_SHORT).show();
        return false;
    }

    private void saveAndReturn() {
        saveCurrent();
        showList();
    }

    private void saveCurrent() {
        if (title == null || store == null) return;
        boolean hasContent = title.length() > 0;
        if (drawing) {
            hasContent |= ink.hasInk();
        } else {
            hasContent |= body.length() > 0;
        }
        if (noteId >= 0 || hasContent) {
            String inkJson = drawing ? ink.serialize() : "";
            noteId = store.save(noteId, title.getText().toString(),
                    drawing ? "" : body.getText().toString(), drawing, inkJson);
        }
    }

    @Override public void onBackPressed() {
        if (!requireTransferIdle()) return;
        if (title != null) saveAndReturn();
        else showList();
    }

    @Override protected void onPause() {
        cancelEditorAutosaves();
        saveCurrent();
        super.onPause();
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(52), 1);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        if (!transferRunning && store != null) {
            store.close();
            store = null;
        }
        super.onDestroy();
    }

    static final class InkView extends View {
        static final float PAGE_WIDTH = 1000f;
        static final float PAGE_HEIGHT = 1414f;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final List<Stroke> strokes = new ArrayList<>();
        private final Runnable onChanged;
        private Stroke active;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private int activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;

        InkView(Activity context, Runnable onChanged) {
            super(context);
            this.onChanged = onChanged;
            setBackgroundColor(Color.rgb(232, 231, 228));
            paint.setColor(Color.rgb(34, 45, 62));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float scale = pageScale();
            float left = (getWidth() - PAGE_WIDTH * scale) / 2f;
            float top = (getHeight() - PAGE_HEIGHT * scale) / 2f;
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setColor(Color.rgb(34, 45, 62));
            for (Stroke stroke : strokes) drawStroke(canvas, stroke);
            if (active != null) drawStroke(canvas, active);
            canvas.restore();
        }

        private float pageScale() {
            if (getWidth() <= 0 || getHeight() <= 0) return 1f;
            return Math.min(getWidth() / PAGE_WIDTH, getHeight() / PAGE_HEIGHT);
        }

        private void drawStroke(android.graphics.Canvas canvas, Stroke stroke) {
            for (int i = 1; i < stroke.points.length(); i++) {
                JSONObject a = stroke.points.optJSONObject(i - 1);
                JSONObject b = stroke.points.optJSONObject(i);
                if (a == null || b == null) continue;
                paint.setStrokeWidth(Math.max(1.0f, (float) b.optDouble("p", 0.5) * 5.0f));
                canvas.drawLine((float) a.optDouble("x"), (float) a.optDouble("y"),
                        (float) b.optDouble("x"), (float) b.optDouble("y"), paint);
            }
            if (stroke.points.length() == 1) {
                JSONObject p = stroke.points.optJSONObject(0);
                if (p != null) {
                    float x = (float) p.optDouble("x");
                    float y = (float) p.optDouble("y");
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    canvas.drawCircle(x, y, Math.max(1f, (float) p.optDouble("p", .5) * 2.5f), paint);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!insidePage(event.getX(0), event.getY(0))) return false;
                active = new Stroke();
                activePointerId = event.getPointerId(0);
                activeToolType = event.getToolType(0);
                addPoint(event, 0, 0, false);
                invalidate();
                onChanged.run();
                return true;
            }
            if (active == null) return false;
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                int pointerIndex = event.getActionIndex();
                int toolType = event.getToolType(pointerIndex);
                if (activeToolType == MotionEvent.TOOL_TYPE_FINGER
                        && (toolType == MotionEvent.TOOL_TYPE_STYLUS
                        || toolType == MotionEvent.TOOL_TYPE_ERASER)
                        && insidePage(event.getX(pointerIndex), event.getY(pointerIndex))) {
                    // Prefer the stylus if a palm/finger made the first contact.
                    active = new Stroke();
                    activePointerId = event.getPointerId(pointerIndex);
                    activeToolType = toolType;
                    addPoint(event, pointerIndex, 0, false);
                    invalidate();
                    onChanged.run();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) return true;
                for (int i = 0; i < event.getHistorySize(); i++) {
                    addPoint(event, pointerIndex, i, true);
                }
                addPoint(event, pointerIndex, 0, false);
                invalidate();
                onChanged.run();
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
                int pointerIndex = event.getActionIndex();
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    addPoint(event, pointerIndex, 0, false);
                    strokes.add(active);
                    active = null;
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
                }
                invalidate();
                onChanged.run();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                // Android may cancel a pointer when the app loses focus or the
                // stylus leaves the view. Keep the points already received so
                // an autosave does not silently discard part of the note.
                if (active.points.length() > 0) strokes.add(active);
                active = null;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
                invalidate();
                onChanged.run();
                return true;
            }
            return true;
        }

        private void addPoint(MotionEvent event, int pointerIndex, int history,
                boolean historical) {
            float screenX = historical ? event.getHistoricalX(pointerIndex, history)
                    : event.getX(pointerIndex);
            float screenY = historical ? event.getHistoricalY(pointerIndex, history)
                    : event.getY(pointerIndex);
            float pressure = historical ? event.getHistoricalPressure(pointerIndex, history)
                    : event.getPressure(pointerIndex);
            addPoint(toPageX(screenX), toPageY(screenY), pressure);
        }

        private boolean insidePage(float x, float y) {
            float scale = pageScale();
            float left = (getWidth() - PAGE_WIDTH * scale) / 2f;
            float top = (getHeight() - PAGE_HEIGHT * scale) / 2f;
            return x >= left && x <= left + PAGE_WIDTH * scale
                    && y >= top && y <= top + PAGE_HEIGHT * scale;
        }

        private float toPageX(float x) {
            float scale = pageScale();
            float left = (getWidth() - PAGE_WIDTH * scale) / 2f;
            return Math.max(0f, Math.min(PAGE_WIDTH, (x - left) / scale));
        }

        private float toPageY(float y) {
            float scale = pageScale();
            float top = (getHeight() - PAGE_HEIGHT * scale) / 2f;
            return Math.max(0f, Math.min(PAGE_HEIGHT, (y - top) / scale));
        }

        private void addPoint(float x, float y, float pressure) {
            JSONObject point = new JSONObject();
            try {
                point.put("x", x);
                point.put("y", y);
                point.put("p", pressure);
                active.points.put(point);
            } catch (JSONException ignored) { }
        }

        void undo() {
            if (!strokes.isEmpty()) {
                strokes.remove(strokes.size() - 1);
                onChanged.run();
            }
            invalidate();
        }

        void clear() {
            strokes.clear();
            active = null;
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            invalidate();
            onChanged.run();
        }

        boolean hasInk() {
            return !strokes.isEmpty() || active != null;
        }

        String serialize() {
            JSONArray array = new JSONArray();
            for (Stroke stroke : strokes) array.put(stroke.points);
            if (active != null && active.points.length() > 0) array.put(active.points);
            return array.toString();
        }

        void restore(String json) {
            strokes.clear();
            active = null;
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    Stroke stroke = new Stroke();
                    stroke.points = array.getJSONArray(i);
                    strokes.add(stroke);
                }
            } catch (JSONException ignored) { }
            invalidate();
        }

        static final class Stroke {
            JSONArray points = new JSONArray();
        }
    }
}
