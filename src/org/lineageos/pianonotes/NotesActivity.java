package org.lineageos.pianonotes;

import android.app.Activity;
import android.graphics.Color;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
    private static final String STATE_FILTER = "filter";
    private static final String STATE_SEARCH = "search";
    private static final int MENU_SAVE = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_EXPORT = 3;
    private static final int MENU_IMPORT = 4;
    private static final int MENU_BACKUP_INFO = 5;
    private static final int MENU_SEARCH = 6;
    private NoteStore store;
    private LinearLayout root;
    private MaterialToolbar toolbar;
    private long noteId = -1;
    private EditText title;
    private EditText body;
    private InkView ink;
    private boolean drawing;
    private boolean transferRunning;
    private int listScrollY;
    private ScrollView noteListScroll;
    private String activeFilter = "All";
    private String searchQuery = "";
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
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        store = new NoteStore(this);
        autosaveHandler = new Handler(Looper.getMainLooper());
        if (state != null) listScrollY = state.getInt(STATE_LIST_SCROLL, 0);
        if (state != null) {
            activeFilter = state.getString(STATE_FILTER, "All");
            searchQuery = state.getString(STATE_SEARCH, "");
        }
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
        state.putString(STATE_FILTER, activeFilter);
        state.putString(STATE_SEARCH, searchQuery);
        super.onSaveInstanceState(state);
    }

    private void base(String heading) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(materialColor(
                com.google.android.material.R.attr.colorSurface, 0xfff7f4f0));
        setContentView(root);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(dp(24) + bars.left, dp(8) + bars.top,
                    dp(24) + bars.right, dp(12) + bars.bottom);
            return windowInsets;
        });
        toolbar = new MaterialToolbar(this);
        toolbar.setTitle(heading);
        toolbar.setTitleCentered(false);
        toolbar.setBackgroundColor(Color.TRANSPARENT);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
        ViewCompat.requestApplyInsets(root);
    }

    private void showList() {
        cancelEditorAutosaves();
        noteId = -1;
        title = null;
        body = null;
        ink = null;
        drawing = false;
        base("Notes");
        List<NoteStore.NoteSummary> notes = store.listSummaries();
        toolbar.setSubtitle(notes.size() == 1 ? "1 note" : notes.size() + " notes");
        toolbar.getMenu().add(0, MENU_EXPORT, 0, "Export backup")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_IMPORT, 1, "Import backup")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_BACKUP_INFO, 2, "Automatic backup")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_SEARCH, 3, "Search notes")
                .setIcon(android.R.drawable.ic_menu_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_EXPORT) chooseExportDestination();
            else if (item.getItemId() == MENU_IMPORT) confirmImport();
            else if (item.getItemId() == MENU_BACKUP_INFO) showBackupInfo();
            else if (item.getItemId() == MENU_SEARCH) showSearchDialog();
            else return false;
            return true;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(24));

        TextView intro = textView("A little space for your ideas", 15,
                materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                        Color.DKGRAY));
        intro.setPadding(dp(4), 0, dp(4), dp(16));
        content.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton text = button("Text note");
        text.setIconResource(R.drawable.ic_note_add_24);
        text.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        text.setIconPadding(dp(10));
        text.setCornerRadius(dp(30));
        text.setOnClickListener(v -> {
            if (requireTransferIdle()) edit(null);
        });
        MaterialButton draw = button("Handwrite");
        draw.setIconResource(R.drawable.ic_draw_24);
        draw.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        draw.setIconPadding(dp(10));
        draw.setCornerRadius(dp(30));
        draw.setBackgroundTintList(ColorStateList.valueOf(materialColor(
                com.google.android.material.R.attr.colorSecondaryContainer, 0xffe6e0ec)));
        draw.setTextColor(materialColor(
                com.google.android.material.R.attr.colorOnSecondaryContainer, Color.DKGRAY));
        draw.setOnClickListener(v -> {
            if (requireTransferIdle()) editInk(null);
        });
        actions.addView(text, weightedButton());
        actions.addView(draw, weightedButton());
        content.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] categories = {"All", "Text", "Handwriting"};
        for (String category : categories) {
            MaterialButton chip = button(category);
            chip.setCheckable(true);
            chip.setChecked(activeFilter.equals(category));
            chip.setCornerRadius(dp(22));
            chip.setStrokeWidth(0);
            chip.setBackgroundTintList(ColorStateList.valueOf(activeFilter.equals(category)
                    ? materialColor(com.google.android.material.R.attr.colorPrimaryContainer,
                            0xffe8e1f2)
                    : materialColor(com.google.android.material.R.attr.colorSurfaceContainerHigh,
                            0xffece8f0)));
            chip.setTextColor(materialColor(activeFilter.equals(category)
                    ? com.google.android.material.R.attr.colorOnPrimaryContainer
                    : com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff544575));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, dp(44));
            chipLp.setMargins(dp(4), dp(14), dp(6), dp(2));
            chipRow.addView(chip, chipLp);
            chip.setOnClickListener(v -> { activeFilter = category; showList(); });
        }
        filters.addView(chipRow);
        content.addView(filters, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        noteListScroll = scroll;
        scroll.setClipToPadding(false);
        FrameLayout frame = new FrameLayout(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(scroll, scrollLp);
        scroll.addView(frame, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                contentWidth(), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        frame.addView(content, contentLp);

        List<NoteStore.NoteSummary> visibleNotes = new ArrayList<>();
        for (NoteStore.NoteSummary note : notes) {
            if (activeFilter.equals("Text") && note.ink) continue;
            if (activeFilter.equals("Handwriting") && !note.ink) continue;
            String haystack = (note.title + " " + note.preview).toLowerCase(java.util.Locale.ROOT);
            if (!searchQuery.isEmpty() && !haystack.contains(searchQuery.toLowerCase(
                    java.util.Locale.ROOT))) continue;
            visibleNotes.add(note);
        }
        if (visibleNotes.isEmpty()) {
            TextView section = textView("RECENT NOTES", 12,
                    materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.GRAY));
            section.setLetterSpacing(.08f);
            section.setPadding(dp(4), dp(28), dp(4), dp(12));
            content.addView(section, new LinearLayout.LayoutParams(-1, -2));

            MaterialCardView emptyCard = card();
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(44), dp(24), dp(44));
            TextView sparkle = textView("✦", 42,
                    materialColor(androidx.appcompat.R.attr.colorPrimary, Color.GRAY));
            sparkle.setGravity(Gravity.CENTER);
            empty.addView(sparkle, new LinearLayout.LayoutParams(-1, -2));
            TextView emptyTitle = textView("Your next idea starts here", 20,
                    materialColor(com.google.android.material.R.attr.colorOnSurface,
                            Color.DKGRAY));
            emptyTitle.setGravity(Gravity.CENTER);
            emptyTitle.setPadding(0, dp(12), 0, dp(4));
            empty.addView(emptyTitle, new LinearLayout.LayoutParams(-1, -2));
            TextView emptyHint = textView("Write a quick thought or start a page with your pen.",
                    14, materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.GRAY));
            emptyHint.setGravity(Gravity.CENTER);
            empty.addView(emptyHint, new LinearLayout.LayoutParams(-1, -2));
            emptyCard.addView(empty);
            content.addView(emptyCard, new LinearLayout.LayoutParams(-1, -2));
        } else {
            TextView section = textView("RECENT NOTES", 12,
                    materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.GRAY));
            section.setLetterSpacing(.08f);
            section.setPadding(dp(4), dp(28), dp(4), dp(12));
            content.addView(section, new LinearLayout.LayoutParams(-1, -2));

            int widthDp = (int) (contentWidth() / getResources().getDisplayMetrics().density);
            int columns = widthDp >= 960 ? 3 : widthDp >= 600 ? 2 : 1;
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(columns);
            grid.setUseDefaultMargins(false);
            content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
            for (int i = 0; i < visibleNotes.size(); i++) {
                NoteStore.NoteSummary note = visibleNotes.get(i);
                GridLayout.LayoutParams cardLp = new GridLayout.LayoutParams(
                        GridLayout.spec(i / columns), GridLayout.spec(i % columns, 1f));
                cardLp.width = 0;
                cardLp.height = -2;
                cardLp.setMargins(dp(4), dp(4), dp(8), dp(8));
                grid.addView(noteCard(note), cardLp);
            }
        }
        scroll.post(() -> scroll.scrollTo(0, listScrollY));
    }

    private void showSearchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Search titles and note text");
        input.setText(searchQuery);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Search notes")
                .setView(input, dp(24), dp(8), dp(24), 0)
                .setNegativeButton("Clear", (dialog, which) -> { searchQuery = ""; showList(); })
                .setPositiveButton("Search", (dialog, which) -> {
                    searchQuery = input.getText().toString().trim(); showList();
                }).show();
    }

    private MaterialCardView noteCard(NoteStore.NoteSummary note) {
        MaterialCardView card = card();
        card.setCardBackgroundColor(materialColor(
                com.google.android.material.R.attr.colorSurfaceContainerLow, 0xfffffefd));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView type = textView(note.ink ? "HANDWRITING" : "TEXT NOTE", 11,
                materialColor(androidx.appcompat.R.attr.colorPrimary,
                        Color.DKGRAY));
        type.setLetterSpacing(.08f);
        type.setTypeface(type.getTypeface(), android.graphics.Typeface.BOLD);
        details.addView(type, new LinearLayout.LayoutParams(-1, -2));

        TextView title = textView(note.title.isEmpty() ? "Untitled" : note.title, 19,
                materialColor(com.google.android.material.R.attr.colorOnSurface,
                        Color.DKGRAY));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setLetterSpacing(-.015f);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(12);
        details.addView(title, titleLp);

        String preview = note.preview.replace('\n', ' ').trim();
        if (preview.length() > 110) preview = preview.substring(0, 110) + "…";
        TextView summary = textView(preview.isEmpty() ? "No preview" : preview, 14,
                materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                        Color.GRAY));
        summary.setMaxLines(4);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(-1, -2);
        summaryLp.topMargin = dp(8);
        details.addView(summary, summaryLp);

        TextView date = textView(android.text.format.DateFormat
                .getMediumDateFormat(this).format(note.updated), 12,
                materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                        Color.GRAY));
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-1, -2);
        dateLp.topMargin = dp(18);
        details.addView(date, dateLp);
        card.addView(details);
        card.setOnClickListener(v -> {
            if (!requireTransferIdle()) return;
            NoteStore.Note selected = store.get(note.id);
            if (selected != null) {
                if (selected.ink) editInk(selected); else edit(selected);
            }
        });
        return card;
    }

    private MaterialCardView card() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(28));
        card.setCardElevation(0);
        card.setCardBackgroundColor(materialColor(
                com.google.android.material.R.attr.colorSurfaceContainerLow, Color.WHITE));
        card.setStrokeColor(materialColor(
                com.google.android.material.R.attr.colorOutlineVariant, 0xffdddddd));
        card.setStrokeWidth(dp(1));
        return card;
    }

    private TextView textView(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int materialColor(int attribute, int fallback) {
        return MaterialColors.getColor(this, attribute, fallback);
    }

    private int contentWidth() {
        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(1200), Math.max(dp(280), displayWidth - dp(80)));
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(60), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("Replace local notes?")
                .setMessage("Import replaces all notes currently stored on this device. "
                        + "Export a backup first if you want to keep them.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose backup", (dialog, which) -> chooseImportSource())
                .show();
    }

    private void showBackupInfo() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Android backup and restore")
                .setMessage("When Google Backup is enabled and its backup service is active, "
                        + "Android Auto Backup can include this app's notes database in your "
                        + "Google account backup. Android controls when backups run, so this "
                        + "screen cannot confirm that a backup has completed. On a replacement "
                        + "phone, use the same Google account and backup dataset. Android "
                        + "restores app data when this same package, signed with the same "
                        + "certificate, is installed. For a portable copy, use Export backup; "
                        + "large libraries may exceed the cloud backup quota.")
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
        base(note == null ? "New note" : "Edit note");
        configureEditorToolbar();

        TextView eyebrow = textView("YOUR THOUGHTS, IN ONE PLACE", 12,
                materialColor(androidx.appcompat.R.attr.colorPrimary, 0xff5e4b8b));
        eyebrow.setLetterSpacing(.12f);
        eyebrow.setTypeface(eyebrow.getTypeface(), android.graphics.Typeface.BOLD);
        eyebrow.setPadding(dp(4), dp(4), dp(4), dp(12));
        LinearLayout.LayoutParams eyebrowLp = new LinearLayout.LayoutParams(contentWidth(), -2);
        eyebrowLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(eyebrow, eyebrowLp);

        title = field("", note == null ? "" : note.title, false);
        TextInputLayout titleLayout = outlinedField("Title", title);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(contentWidth(), -2);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        titleLp.bottomMargin = dp(14);
        root.addView(titleLayout, titleLp);

        body = field("Start writing…", note == null ? "" : note.body, true);
        body.setMinLines(10);
        body.setPadding(dp(22), dp(24), dp(22), dp(24));
        body.setBackgroundColor(Color.TRANSPARENT);
        body.setHintTextColor(materialColor(
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff686570));
        MaterialCardView paper = card();
        paper.setRadius(dp(30));
        paper.setCardBackgroundColor(materialColor(
                com.google.android.material.R.attr.colorSurfaceContainerLowest, 0xfffffefd));
        paper.addView(body, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams paperLp = new LinearLayout.LayoutParams(contentWidth(), 0, 1);
        paperLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(paper, paperLp);
        lastSavedTitle = title.getText().toString();
        lastSavedBody = body.getText().toString();
    }

    private void editInk(NoteStore.Note note) {
        rememberListPosition();
        drawing = true;
        noteId = note == null ? -1 : note.id;
        base(note == null ? "New ink note" : "Handwriting");
        configureEditorToolbar();

        TextView eyebrow = textView("A PAGE FOR YOUR PEN", 12,
                materialColor(androidx.appcompat.R.attr.colorPrimary, 0xff5e4b8b));
        eyebrow.setLetterSpacing(.12f);
        eyebrow.setTypeface(eyebrow.getTypeface(), android.graphics.Typeface.BOLD);
        eyebrow.setPadding(dp(4), dp(4), dp(4), dp(12));
        LinearLayout.LayoutParams eyebrowLp = new LinearLayout.LayoutParams(contentWidth(), -2);
        eyebrowLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(eyebrow, eyebrowLp);

        title = field("", note == null ? "" : note.title, false);
        TextInputLayout titleLayout = outlinedField("Title", title);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(contentWidth(), -2);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        titleLp.bottomMargin = dp(12);
        root.addView(titleLayout, titleLp);

        ink = new InkView(this, this::scheduleInkAutosave);
        if (note != null) ink.restore(note.inkJson);
        MaterialCardView paper = card();
        paper.setRadius(dp(30));
        paper.addView(ink, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams inkLp = new LinearLayout.LayoutParams(contentWidth(), 0, 1);
        inkLp.gravity = Gravity.CENTER_HORIZONTAL;
        inkLp.bottomMargin = dp(12);
        root.addView(paper, inkLp);
        addInkActions();
    }

    private void configureEditorToolbar() {
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24);
        toolbar.setNavigationIconTint(materialColor(
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY));
        toolbar.setNavigationContentDescription("Back");
        toolbar.setNavigationOnClickListener(v -> saveAndReturn());
        toolbar.getMenu().add(0, MENU_SAVE, 0, "Save")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS
                        | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        if (noteId >= 0) {
            toolbar.getMenu().add(0, MENU_DELETE, 1, "Delete")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_SAVE) {
                saveCurrent();
                showList();
            } else if (item.getItemId() == MENU_DELETE) {
                confirmDelete();
            } else {
                return false;
            }
            return true;
        });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this note?")
                .setMessage("This note will be permanently deleted from this device.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    rememberListPosition();
                    store.delete(noteId);
                    showList();
                })
                .show();
    }

    private void addInkActions() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton undo = button("Undo");
        setTonalButton(undo);
        undo.setOnClickListener(v -> ink.undo());
        actions.addView(undo, weightedButton());

        MaterialButton eraser = button("Eraser");
        eraser.setCheckable(true);
        setTonalButton(eraser);
        eraser.setOnClickListener(v -> {
            boolean enabled = eraser.isChecked();
            ink.setEraserEnabled(enabled);
            int background = enabled
                    ? materialColor(com.google.android.material.R.attr.colorPrimaryContainer,
                            0xffeaddff)
                    : materialColor(com.google.android.material.R.attr.colorSurfaceContainerHigh,
                            Color.LTGRAY);
            int foreground = enabled
                    ? materialColor(com.google.android.material.R.attr.colorOnPrimaryContainer,
                            Color.DKGRAY)
                    : materialColor(com.google.android.material.R.attr.colorOnSurface,
                            Color.DKGRAY);
            eraser.setBackgroundTintList(ColorStateList.valueOf(background));
            eraser.setTextColor(foreground);
        });
        actions.addView(eraser, weightedButton());

        MaterialButton clear = button("Clear page");
        setTonalButton(clear);
        clear.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Clear this page?")
                .setMessage("All handwriting on this page will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> ink.clear())
                .show());
        actions.addView(clear, weightedButton());
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(contentWidth(), -2);
        actionsLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(actions, actionsLp);
    }

    private void setTonalButton(MaterialButton button) {
        button.setBackgroundTintList(ColorStateList.valueOf(materialColor(
                com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.LTGRAY)));
        button.setTextColor(materialColor(
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY));
    }

    private EditText field(String hint, String value, boolean multiline) {
        TextInputEditText view = new TextInputEditText(this);
        view.setSingleLine(!multiline);
        view.setHint(hint);
        view.setText(value);
        view.setTextSize(multiline ? 18 : 20);
        view.setTextColor(materialColor(
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY));
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

    private TextInputLayout outlinedField(String hint, EditText input) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return layout;
    }

    private MaterialButton button(String text) {
        MaterialButton b = new MaterialButton(this);
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
        private final int pageColor;
        private final int inkColor;
        private Stroke active;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private int activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        private boolean eraserEnabled;
        private boolean erasingGesture;

        InkView(Activity context, Runnable onChanged) {
            super(context);
            this.onChanged = onChanged;
            setBackgroundColor(MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                    Color.LTGRAY));
            pageColor = MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorSurface, Color.WHITE);
            inkColor = MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY);
            paint.setColor(inkColor);
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
            paint.setColor(pageColor);
            canvas.drawRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setColor(inkColor);
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
                double pressure = b.optDouble("p", 0.5);
                paint.setStrokeWidth((float) Math.max(1.0, Math.min(12.0, pressure * 5.0)));
                canvas.drawLine((float) a.optDouble("x"), (float) a.optDouble("y"),
                        (float) b.optDouble("x"), (float) b.optDouble("y"), paint);
            }
            if (stroke.points.length() == 1) {
                JSONObject p = stroke.points.optJSONObject(0);
                if (p != null) {
                    float x = (float) p.optDouble("x");
                    float y = (float) p.optDouble("y");
                    paint.setStyle(android.graphics.Paint.Style.FILL);
                    double pressure = p.optDouble("p", .5);
                    float radius = (float) Math.max(1.0, Math.min(6.0, pressure * 2.5));
                    canvas.drawCircle(x, y, radius, paint);
                    paint.setStyle(android.graphics.Paint.Style.STROKE);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!insidePage(event.getX(0), event.getY(0))) return false;
                activePointerId = event.getPointerId(0);
                activeToolType = event.getToolType(0);
                erasingGesture = eraserEnabled || activeToolType == MotionEvent.TOOL_TYPE_ERASER;
                if (erasingGesture) {
                    if (eraseAt(event.getX(0), event.getY(0))) {
                        invalidate();
                        onChanged.run();
                    }
                } else {
                    active = new Stroke();
                    addPoint(event, 0, 0, false);
                    invalidate();
                    onChanged.run();
                }
                return true;
            }
            if (active == null && !erasingGesture) return false;
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                int pointerIndex = event.getActionIndex();
                int toolType = event.getToolType(pointerIndex);
                if (activeToolType == MotionEvent.TOOL_TYPE_FINGER
                        && (toolType == MotionEvent.TOOL_TYPE_STYLUS
                        || toolType == MotionEvent.TOOL_TYPE_ERASER)
                        && insidePage(event.getX(pointerIndex), event.getY(pointerIndex))) {
                    // Preserve the initial touch stroke and prefer the stylus if
                    // a palm/finger made first contact.
                    if (active != null && active.points.length() > 0) {
                        strokes.add(active);
                    }
                    activePointerId = event.getPointerId(pointerIndex);
                    activeToolType = toolType;
                    erasingGesture = eraserEnabled || toolType == MotionEvent.TOOL_TYPE_ERASER;
                    if (erasingGesture) {
                        active = null;
                        if (eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))) {
                            onChanged.run();
                        }
                    } else {
                        active = new Stroke();
                        addPoint(event, pointerIndex, 0, false);
                        onChanged.run();
                    }
                    invalidate();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) return true;
                if (erasingGesture) {
                    boolean changed = false;
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        if (eraseAt(event.getHistoricalX(pointerIndex, i),
                                event.getHistoricalY(pointerIndex, i))) changed = true;
                    }
                    if (eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))) {
                        changed = true;
                    }
                    if (changed) {
                        invalidate();
                        onChanged.run();
                    }
                    return true;
                }
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
                    if (!erasingGesture && active != null) {
                        addPoint(event, pointerIndex, 0, false);
                        strokes.add(active);
                    }
                    active = null;
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
                    erasingGesture = false;
                }
                invalidate();
                onChanged.run();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                // Android may cancel a pointer when the app loses focus or the
                // stylus leaves the view. Keep the points already received so
                // an autosave does not silently discard part of the note.
                if (active != null && active.points.length() > 0) strokes.add(active);
                active = null;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                activeToolType = MotionEvent.TOOL_TYPE_UNKNOWN;
                erasingGesture = false;
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

        private boolean eraseAt(float screenX, float screenY) {
            if (!insidePage(screenX, screenY)) return false;
            float x = toPageX(screenX);
            float y = toPageY(screenY);
            float radius = 22f / pageScale();
            float radiusSquared = radius * radius;
            for (int i = strokes.size() - 1; i >= 0; i--) {
                if (strokeWithinRadius(strokes.get(i), x, y, radiusSquared)) {
                    strokes.remove(i);
                    return true;
                }
            }
            return false;
        }

        private boolean strokeWithinRadius(Stroke stroke, float x, float y,
                float radiusSquared) {
            if (stroke.points.length() == 1) {
                JSONObject point = stroke.points.optJSONObject(0);
                return point != null && distanceSquared(x, y,
                        (float) point.optDouble("x"), (float) point.optDouble("y"))
                        <= radiusSquared;
            }
            for (int i = 1; i < stroke.points.length(); i++) {
                JSONObject first = stroke.points.optJSONObject(i - 1);
                JSONObject second = stroke.points.optJSONObject(i);
                if (first == null || second == null) continue;
                if (segmentDistanceSquared(x, y,
                        (float) first.optDouble("x"), (float) first.optDouble("y"),
                        (float) second.optDouble("x"), (float) second.optDouble("y"))
                        <= radiusSquared) return true;
            }
            return false;
        }

        private float segmentDistanceSquared(float x, float y, float x1, float y1,
                float x2, float y2) {
            float dx = x2 - x1;
            float dy = y2 - y1;
            float lengthSquared = dx * dx + dy * dy;
            if (lengthSquared == 0f) return distanceSquared(x, y, x1, y1);
            float position = ((x - x1) * dx + (y - y1) * dy) / lengthSquared;
            position = Math.max(0f, Math.min(1f, position));
            return distanceSquared(x, y, x1 + position * dx, y1 + position * dy);
        }

        private float distanceSquared(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2;
            float dy = y1 - y2;
            return dx * dx + dy * dy;
        }

        void setEraserEnabled(boolean enabled) {
            eraserEnabled = enabled;
        }

        boolean isEraserEnabled() {
            return eraserEnabled;
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
