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
import android.text.Editable;
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
    private static final int MENU_PIN = 7;
    private static final int MENU_MOVE = 8;
    private static final int MENU_EMPTY_TRASH = 9;
    private static final String FILTER_PINNED = "Pinned";
    private static final String FILTER_TRASH = "Trash";
    private static final String FOLDER_PREFIX = "folder:";
    private static final String FILTER_UNCATEGORIZED = FOLDER_PREFIX + "0";
    private static final int MENU_VIEW = 10;
    private NoteStore store;
    private LinearLayout root;
    private FrameLayout screen;
    /** Where the editor is built: the whole screen, or the right pane on a tablet. */
    private LinearLayout editorHost;
    private LinearLayout editorPane;
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
        String currentBody = bodyState();
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
        store.purgeTrash();
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
        screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
        editorHost = root;
        editorPane = null;
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
        int live = 0;
        for (NoteStore.NoteSummary note : notes) if (!note.deleted) live++;
        toolbar.setSubtitle(live == 1 ? "1 note" : live + " notes");
        toolbar.getMenu().add(0, MENU_EXPORT, 0, "Export backup")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_IMPORT, 1, "Import backup")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_BACKUP_INFO, 2, "Automatic backup")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_SEARCH, 3, "Search notes")
                .setIcon(android.R.drawable.ic_menu_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        toolbar.getMenu().add(0, MENU_VIEW, 4, gridView() ? "List view" : "Grid view")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        if (activeFilter.equals(FILTER_TRASH)) {
            toolbar.getMenu().add(0, MENU_EMPTY_TRASH, 4, "Empty trash")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_EMPTY_TRASH) confirmEmptyTrash();
            else if (item.getItemId() == MENU_VIEW) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("grid", !gridView()).apply();
                showList();
            }
            else if (item.getItemId() == MENU_EXPORT) chooseExportDestination();
            else if (item.getItemId() == MENU_IMPORT) confirmImport();
            else if (item.getItemId() == MENU_BACKUP_INFO) showBackupInfo();
            else if (item.getItemId() == MENU_SEARCH) showSearchDialog();
            else return false;
            return true;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(24));

        com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fab =
                new com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton(this);
        fab.setText("New note");
        fab.setIconResource(R.drawable.ic_note_add_24);
        fab.setOnClickListener(v -> {
            if (!requireTransferIdle()) return;
            saveCurrent();
            new MaterialAlertDialogBuilder(this)
                    .setTitle("New note")
                    .setItems(new String[]{"Text note", "Handwriting"}, (d, which) -> {
                        if (which == 0) edit(null); else editInk(null);
                    })
                    .show();
        });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.END);
        fabLp.setMargins(dp(24), dp(24), dp(32), dp(32));
        screen.addView(fab, fabLp);

        boolean wide = isWide();
        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        // Tablets get the Xiaomi Notes folder sidebar; phones keep a chip row.
        chipRow.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        List<NoteStore.Folder> folders = store.listFolders();
        List<String> categories = new ArrayList<>(java.util.Arrays.asList(
                "All", FILTER_PINNED, "Text", "Handwriting", FILTER_UNCATEGORIZED));
        for (NoteStore.Folder folder : folders) categories.add(FOLDER_PREFIX + folder.id);
        categories.add(FILTER_TRASH);
        if (activeFilter.startsWith(FOLDER_PREFIX) && !categories.contains(activeFilter)) {
            activeFilter = "All";
        }
        for (String category : categories) {
            NoteStore.Folder folder = folderFor(folders, category);
            MaterialButton chip = button(folder != null ? folder.name : label(category));
            if (wide) chip.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            if (folder != null) {
                chip.setOnLongClickListener(v -> { editFolder(folder); return true; });
            }
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
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(wide ? -1 : -2, dp(44));
            if (wide) chipLp.setMargins(0, dp(2), dp(12), dp(2));
            else chipLp.setMargins(dp(4), dp(14), dp(6), dp(2));
            chipRow.addView(chip, chipLp);
            chip.setOnClickListener(v -> { activeFilter = category; showList(); });
        }
        MaterialButton newFolder = button(wide ? "+ New folder" : "+ Folder");
        newFolder.setCornerRadius(dp(22));
        setTonalButton(newFolder);
        LinearLayout.LayoutParams newFolderLp = new LinearLayout.LayoutParams(wide ? -1 : -2, dp(44));
        if (wide) newFolderLp.setMargins(0, dp(10), dp(12), dp(2));
        else newFolderLp.setMargins(dp(4), dp(14), dp(6), dp(2));
        chipRow.addView(newFolder, newFolderLp);
        newFolder.setOnClickListener(v -> editFolder(null));
        ScrollView scroll = new ScrollView(this);
        noteListScroll = scroll;
        scroll.setClipToPadding(false);
        FrameLayout frame = new FrameLayout(this);
        if (wide) {
            // Folders | note list | open note, like Xiaomi Notes on a tablet.
            LinearLayout panes = new LinearLayout(this);
            panes.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(panes, new LinearLayout.LayoutParams(-1, 0, 1));
            ScrollView sidebar = new ScrollView(this);
            sidebar.addView(chipRow, new ScrollView.LayoutParams(-1, -2));
            panes.addView(sidebar, new LinearLayout.LayoutParams(dp(240), -1));
            panes.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
            editorPane = new LinearLayout(this);
            editorPane.setOrientation(LinearLayout.VERTICAL);
            editorPane.setPadding(dp(16), 0, 0, 0);
            panes.addView(editorPane, new LinearLayout.LayoutParams(0, -1, 1.4f));
            TextView placeholder = textView("Select a note, or tap New note", 16,
                    materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.GRAY));
            placeholder.setGravity(Gravity.CENTER);
            editorPane.addView(placeholder, new LinearLayout.LayoutParams(-1, -1));
        } else {
            filters.addView(chipRow);
            content.addView(filters, new LinearLayout.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        scroll.addView(frame, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                wide ? -1 : contentWidth(), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        frame.addView(content, contentLp);

        List<NoteStore.NoteSummary> visibleNotes = new ArrayList<>();
        for (NoteStore.NoteSummary note : notes) {
            if (activeFilter.equals(FILTER_TRASH) != note.deleted) continue;
            if (activeFilter.equals(FILTER_PINNED) && !note.pinned) continue;
            if (activeFilter.startsWith(FOLDER_PREFIX)
                    && note.folderId != currentFolder()) continue;
            if (activeFilter.equals("Text") && note.ink) continue;
            if (activeFilter.equals("Handwriting") && !note.ink) continue;
            String haystack = (note.title + " " + note.preview).toLowerCase(java.util.Locale.ROOT);
            if (!searchQuery.isEmpty() && !haystack.contains(searchQuery.toLowerCase(
                    java.util.Locale.ROOT))) continue;
            visibleNotes.add(note);
        }
        String sectionName = activeFilter.equals(FILTER_TRASH)
                ? "TRASH · DELETED AFTER 30 DAYS" : "RECENT NOTES";
        if (visibleNotes.isEmpty()) {
            TextView section = textView(sectionName, 12,
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
            TextView section = textView(sectionName, 12,
                    materialColor(com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.GRAY));
            section.setLetterSpacing(.08f);
            section.setPadding(dp(4), dp(28), dp(4), dp(12));
            content.addView(section, new LinearLayout.LayoutParams(-1, -2));

            int widthDp = (int) (contentWidth() / getResources().getDisplayMetrics().density);
            int columns = !gridView() ? 1 : wide ? 2 : widthDp >= 960 ? 3 : widthDp >= 600 ? 2 : 1;
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

    private boolean isWide() {
        return getResources().getConfiguration().screenWidthDp >= 840;
    }

    private boolean gridView() {
        return getPreferences(MODE_PRIVATE).getBoolean("grid", true);
    }

    private static String label(String category) {
        switch (category) {
            case "All": return "All notes";
            case FILTER_UNCATEGORIZED: return "Uncategorized";
            case FILTER_TRASH: return "Recently deleted";
            default: return category;
        }
    }

    /** Clears the screen or the tablet's note pane for the editor. */
    private void openEditor(String heading) {
        if (!isWide()) {
            base(heading);
            return;
        }
        long keep = noteId;
        boolean keepDrawing = drawing;
        showList();
        noteId = keep;
        drawing = keepDrawing;
        editorPane.removeAllViews();
        editorHost = editorPane;
        toolbar = new MaterialToolbar(this);
        toolbar.setTitle(heading);
        toolbar.setBackgroundColor(Color.TRANSPARENT);
        editorHost.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private int editorWidth() {
        return editorHost == root ? contentWidth() : -1;
    }

    private long currentFolder() {
        return activeFilter.startsWith(FOLDER_PREFIX)
                ? Long.parseLong(activeFilter.substring(FOLDER_PREFIX.length())) : 0;
    }

    private static NoteStore.Folder folderFor(List<NoteStore.Folder> folders, String category) {
        if (!category.startsWith(FOLDER_PREFIX)) return null;
        long id = Long.parseLong(category.substring(FOLDER_PREFIX.length()));
        for (NoteStore.Folder folder : folders) if (folder.id == id) return folder;
        return null;
    }

    /** Creates a folder, or renames or deletes an existing one. */
    private void editFolder(NoteStore.Folder folder) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Folder name");
        if (folder != null) input.setText(folder.name);
        var dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(folder == null ? "New folder" : "Edit folder")
                .setView(input, dp(24), dp(8), dp(24), 0)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(folder == null ? "Create" : "Rename", (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (folder == null) activeFilter = FOLDER_PREFIX + store.addFolder(name);
                    else store.renameFolder(folder.id, name);
                    showList();
                });
        if (folder != null) {
            dialog.setNeutralButton("Delete folder", (d, which) -> {
                store.deleteFolder(folder.id);
                activeFilter = "All";
                showList();
            });
        }
        dialog.show();
    }

    private void showNoteActions(NoteStore.NoteSummary note) {
        if (note.deleted) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(note.title.isEmpty() ? "Untitled" : note.title)
                    .setItems(new String[]{"Restore", "Delete forever"}, (d, which) -> {
                        rememberListPosition();
                        if (which == 0) store.restore(note.id); else store.delete(note.id);
                        showList();
                    })
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(note.title.isEmpty() ? "Untitled" : note.title)
                .setItems(new String[]{note.pinned ? "Unpin" : "Pin to top",
                        "Move to folder", "Move to trash"}, (d, which) -> {
                    rememberListPosition();
                    if (which == 0) {
                        store.setPinned(note.id, !note.pinned);
                        showList();
                    } else if (which == 1) {
                        chooseFolder(note.id, this::showList);
                    } else {
                        store.trash(note.id);
                        showList();
                    }
                })
                .show();
    }

    private void chooseFolder(long id, Runnable done) {
        List<NoteStore.Folder> folders = store.listFolders();
        String[] names = new String[folders.size() + 1];
        names[0] = "No folder";
        for (int i = 0; i < folders.size(); i++) names[i + 1] = folders.get(i).name;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Move to folder")
                .setItems(names, (d, which) -> {
                    store.moveToFolder(id, which == 0 ? 0 : folders.get(which - 1).id);
                    done.run();
                })
                .show();
    }

    private void confirmEmptyTrash() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Empty trash?")
                .setMessage("Notes in the trash will be permanently deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Empty", (d, which) -> {
                    store.emptyTrash();
                    showList();
                })
                .show();
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

        TextView type = textView((note.pinned ? "PINNED · " : "")
                + (note.ink ? "HANDWRITING" : "TEXT NOTE"), 11,
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
        card.setOnLongClickListener(v -> {
            if (requireTransferIdle()) showNoteActions(note);
            return true;
        });
        card.setOnClickListener(v -> {
            if (!requireTransferIdle()) return;
            saveCurrent();
            if (note.deleted) {
                showNoteActions(note);
                return;
            }
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
        openEditor(note == null ? "New note" : "Edit note");
        configureEditorToolbar();

        TextView eyebrow = textView("YOUR THOUGHTS, IN ONE PLACE", 12,
                materialColor(androidx.appcompat.R.attr.colorPrimary, 0xff5e4b8b));
        eyebrow.setLetterSpacing(.12f);
        eyebrow.setTypeface(eyebrow.getTypeface(), android.graphics.Typeface.BOLD);
        eyebrow.setPadding(dp(4), dp(4), dp(4), dp(12));
        LinearLayout.LayoutParams eyebrowLp = new LinearLayout.LayoutParams(editorWidth(), -2);
        eyebrowLp.gravity = Gravity.CENTER_HORIZONTAL;
        editorHost.addView(eyebrow, eyebrowLp);

        title = field("", note == null ? "" : note.title, false);
        TextInputLayout titleLayout = outlinedField("Title", title);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(editorWidth(), -2);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        titleLp.bottomMargin = dp(14);
        editorHost.addView(titleLayout, titleLp);

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
        LinearLayout.LayoutParams paperLp = new LinearLayout.LayoutParams(editorWidth(), 0, 1);
        paperLp.gravity = Gravity.CENTER_HORIZONTAL;
        editorHost.addView(paper, paperLp);
        if (note != null) RichText.apply(body.getText(), note.spans);
        body.addTextChangedListener(new ListContinuation());
        body.setOnTouchListener(this::toggleCheckOnTap);
        editorHost.addView(formatBar(), editorHost.indexOfChild(paper));
        lastSavedTitle = title.getText().toString();
        lastSavedBody = bodyState();
    }

    private String bodyState() {
        return body.getText().toString() + '\u0000' + RichText.serialize(body.getText());
    }

    private void scheduleTextAutosave() {
        if (autosaveHandler == null) return;
        autosaveHandler.removeCallbacks(textAutosave);
        autosaveHandler.postDelayed(textAutosave, 700);
    }

    /** The Xiaomi Notes formatting bar: styles, lists, checklist, indent and divider. */
    private View formatBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, 0, 0, dp(8));
        scroller.addView(bar);
        addFormat(bar, "B", "Bold", () -> style(RichText.BOLD));
        addFormat(bar, "H1", "First-level heading", () -> style(RichText.HEADING_1));
        addFormat(bar, "H2", "Second-level heading", () -> style(RichText.HEADING_2));
        addFormat(bar, "▌", "Highlight", () -> style(RichText.HIGHLIGHT));
        addFormat(bar, "U", "Underline", () -> style(RichText.UNDERLINE));
        addFormat(bar, "S", "Strikethrough", () -> style(RichText.STRIKE));
        addFormat(bar, "•", "Bulleted list", () -> toggleMarker(RichText.BULLET));
        addFormat(bar, "1.", "Numbered list", () -> toggleMarker("1. "));
        addFormat(bar, "☐", "Checklist", () -> toggleMarker(RichText.CHECK_OPEN));
        addFormat(bar, "⇥", "Increase indent", () -> indent(true));
        addFormat(bar, "⇤", "Decrease indent", () -> indent(false));
        addFormat(bar, "—", "Divider", this::insertDivider);
        return scroller;
    }

    private void addFormat(LinearLayout bar, String label, String description, Runnable action) {
        MaterialButton button = button(label);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setCornerRadius(dp(16));
        setTonalButton(button);
        button.setOnClickListener(v -> {
            action.run();
            scheduleTextAutosave();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
        lp.setMargins(0, 0, dp(6), 0);
        bar.addView(button, lp);
    }

    private void style(String type) {
        int start = Math.min(body.getSelectionStart(), body.getSelectionEnd());
        int end = Math.max(body.getSelectionStart(), body.getSelectionEnd());
        if (start == end) {
            // No selection: style the current line, like Xiaomi Notes does for headings.
            start = RichText.lineStart(body.getText(), start);
            end = RichText.lineEnd(body.getText(), end);
        }
        RichText.toggle(body.getText(), type, start, end);
    }

    /** Adds the marker to every selected line, or removes it when they all have it. */
    private void toggleMarker(String marker) {
        Editable text = body.getText();
        int first = RichText.lineStart(text, Math.min(body.getSelectionStart(), body.getSelectionEnd()));
        int last = RichText.lineEnd(text, Math.max(body.getSelectionStart(), body.getSelectionEnd()));
        List<Integer> starts = new ArrayList<>();
        for (int i = first; i <= last; i = RichText.lineEnd(text, i) + 1) {
            starts.add(i);
            if (RichText.lineEnd(text, i) >= last) break;
        }
        boolean numbered = marker.equals("1. ");
        boolean allHave = true;
        for (int start : starts) {
            String current = RichText.markerOf(line(text, start));
            boolean has = numbered ? current != null && current.matches("\\d+\\. ")
                    : marker.equals(current) || (marker.equals(RichText.CHECK_OPEN)
                            && RichText.CHECK_DONE.equals(current));
            allHave &= has;
        }
        for (int k = starts.size() - 1; k >= 0; k--) {
            int start = starts.get(k);
            int at = start + RichText.indentOf(line(text, start));
            String current = RichText.markerOf(line(text, start));
            if (current != null) text.delete(at, at + current.length());
            if (!allHave) text.insert(at, numbered ? (k + 1) + ". " : marker);
        }
    }

    private void indent(boolean in) {
        Editable text = body.getText();
        int start = RichText.lineStart(text, body.getSelectionStart());
        if (in) {
            text.insert(start, RichText.INDENT);
        } else if (RichText.indentOf(line(text, start)) > 0) {
            text.delete(start, start + RichText.INDENT.length());
        }
    }

    private void insertDivider() {
        Editable text = body.getText();
        int at = RichText.lineEnd(text, body.getSelectionEnd());
        text.insert(at, "\n" + RichText.DIVIDER + "\n");
        body.setSelection(Math.min(text.length(), at + RichText.DIVIDER.length() + 2));
    }

    private static CharSequence line(CharSequence text, int start) {
        return text.subSequence(start, RichText.lineEnd(text, start));
    }

    /** Tapping a checkbox marks the item done or not done. */
    private boolean toggleCheckOnTap(View view, MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return false;
        int offset = body.getOffsetForPosition(event.getX(), event.getY());
        Editable text = body.getText();
        int start = RichText.lineStart(text, offset);
        int at = start + RichText.indentOf(line(text, start));
        String marker = RichText.markerOf(line(text, start));
        if (offset > at + 2 || !(RichText.CHECK_OPEN.equals(marker)
                || RichText.CHECK_DONE.equals(marker))) return false;
        boolean done = RichText.CHECK_OPEN.equals(marker);
        text.replace(at, at + marker.length(), done ? RichText.CHECK_DONE : RichText.CHECK_OPEN);
        int end = RichText.lineEnd(text, at);
        int itemStart = at + marker.length();
        // A done item is crossed out, as in Xiaomi Notes.
        if (itemStart < end) {
            for (android.text.style.StrikethroughSpan span
                    : text.getSpans(itemStart, end, android.text.style.StrikethroughSpan.class)) {
                text.removeSpan(span);
            }
            if (done) RichText.toggle(text, RichText.STRIKE, itemStart, end);
        }
        scheduleTextAutosave();
        return true;
    }

    /** Enter on a list line starts the next item; Enter on an empty item ends the list. */
    private final class ListContinuation implements android.text.TextWatcher {
        private boolean editing;
        private int newline = -1;

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            newline = !editing && count == 1 && before == 0 && s.charAt(start) == '\n' ? start : -1;
        }

        @Override public void afterTextChanged(Editable text) {
            if (editing || newline < 0) return;
            int previous = RichText.lineStart(text, newline);
            CharSequence line = line(text, previous);
            String marker = RichText.markerOf(line);
            if (marker == null) return;
            int indent = RichText.indentOf(line);
            editing = true;
            if (line.length() == indent + marker.length()) {
                text.delete(previous, newline + 1);
            } else {
                String next = marker.matches("\\d+\\. ")
                        ? (Integer.parseInt(marker.substring(0, marker.indexOf('.'))) + 1) + ". "
                        : RichText.CHECK_DONE.equals(marker) ? RichText.CHECK_OPEN : marker;
                text.insert(newline + 1, line.subSequence(0, indent) + next);
            }
            editing = false;
            newline = -1;
        }
    }

    private void editInk(NoteStore.Note note) {
        rememberListPosition();
        drawing = true;
        noteId = note == null ? -1 : note.id;
        openEditor(note == null ? "New ink note" : "Handwriting");
        configureEditorToolbar();

        TextView eyebrow = textView("A PAGE FOR YOUR PEN", 12,
                materialColor(androidx.appcompat.R.attr.colorPrimary, 0xff5e4b8b));
        eyebrow.setLetterSpacing(.12f);
        eyebrow.setTypeface(eyebrow.getTypeface(), android.graphics.Typeface.BOLD);
        eyebrow.setPadding(dp(4), dp(4), dp(4), dp(12));
        LinearLayout.LayoutParams eyebrowLp = new LinearLayout.LayoutParams(editorWidth(), -2);
        eyebrowLp.gravity = Gravity.CENTER_HORIZONTAL;
        editorHost.addView(eyebrow, eyebrowLp);

        title = field("", note == null ? "" : note.title, false);
        TextInputLayout titleLayout = outlinedField("Title", title);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(editorWidth(), -2);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        titleLp.bottomMargin = dp(12);
        editorHost.addView(titleLayout, titleLp);

        ink = new InkView(this, this::scheduleInkAutosave);
        if (note != null) ink.restore(note.inkJson);
        MaterialCardView paper = card();
        paper.setRadius(dp(30));
        paper.addView(ink, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams inkLp = new LinearLayout.LayoutParams(editorWidth(), 0, 1);
        inkLp.gravity = Gravity.CENTER_HORIZONTAL;
        inkLp.bottomMargin = dp(12);
        editorHost.addView(PenBar.wrap(this, ink, paper), inkLp);
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
        toolbar.getMenu().add(0, MENU_PIN, 1, "Pin or unpin")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_MOVE, 2, "Move to folder")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.getMenu().add(0, MENU_DELETE, 3, "Move to trash")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_SAVE) {
                saveCurrent();
                showList();
            } else if (item.getItemId() == MENU_DELETE) {
                saveCurrent();
                if (noteId >= 0) store.trash(noteId);
                showList();
            } else if (item.getItemId() == MENU_PIN || item.getItemId() == MENU_MOVE) {
                saveCurrent();
                if (noteId < 0) {
                    Toast.makeText(this, "Write something first.", Toast.LENGTH_SHORT).show();
                } else if (item.getItemId() == MENU_PIN) {
                    NoteStore.NoteSummary current = summary(noteId);
                    boolean pin = current == null || !current.pinned;
                    store.setPinned(noteId, pin);
                    Toast.makeText(this, pin ? "Pinned" : "Unpinned", Toast.LENGTH_SHORT).show();
                } else {
                    chooseFolder(noteId, () -> Toast.makeText(this, "Moved",
                            Toast.LENGTH_SHORT).show());
                }
            } else {
                return false;
            }
            return true;
        });
    }

    private NoteStore.NoteSummary summary(long id) {
        for (NoteStore.NoteSummary note : store.listSummaries()) if (note.id == id) return note;
        return null;
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
            boolean created = noteId < 0;
            noteId = store.save(noteId, title.getText().toString(),
                    drawing ? "" : body.getText().toString(), drawing, inkJson,
                    drawing ? "" : RichText.serialize(body.getText()));
            // A note written while a folder is open belongs to that folder.
            if (created && currentFolder() > 0) store.moveToFolder(noteId, currentFolder());
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
}
