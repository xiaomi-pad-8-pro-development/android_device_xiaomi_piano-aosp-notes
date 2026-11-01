package com.alxp.pianonotes;

import android.app.Activity;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Xiaomi Notes recordings: record with bookmarks, then play and jump to them. */
final class AudioNotes {
    private AudioNotes() {}

    /** Records until Stop; hands the new file name to {@code done}. */
    static void record(Activity activity, Consumer<String> done) {
        String name = Attachments.newName("m4a");
        File file = Attachments.file(activity, name);
        MediaRecorder recorder = android.os.Build.VERSION.SDK_INT >= 31
                ? new MediaRecorder(activity) : new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(file.getPath());
            recorder.prepare();
            recorder.start();
        } catch (IOException | RuntimeException failure) {
            recorder.release();
            file.delete();
            Toast.makeText(activity, "Could not start recording: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        long started = SystemClock.elapsedRealtime();
        List<Long> marks = new ArrayList<>();
        Handler handler = new Handler(Looper.getMainLooper());

        LinearLayout box = column(activity);
        TextView clock = big(activity, "0:00");
        box.addView(clock);
        TextView markCount = small(activity, "Tap Bookmark to mark a moment");
        box.addView(markCount);
        MaterialButton bookmark = new MaterialButton(activity);
        bookmark.setText("Bookmark");
        bookmark.setOnClickListener(v -> {
            marks.add(SystemClock.elapsedRealtime() - started);
            markCount.setText(marks.size() == 1 ? "1 bookmark" : marks.size() + " bookmarks");
        });
        box.addView(bookmark);
        Runnable tick = new Runnable() {
            @Override public void run() {
                clock.setText(Attachments.time(SystemClock.elapsedRealtime() - started));
                handler.postDelayed(this, 500);
            }
        };
        handler.post(tick);

        boolean[] finished = {false};
        Runnable stop = () -> {
            if (finished[0]) return;
            finished[0] = true;
            handler.removeCallbacks(tick);
            try {
                recorder.stop();
            } catch (RuntimeException tooShort) {
                // Stopping right after starting leaves no audio.
                recorder.release();
                file.delete();
                return;
            }
            recorder.release();
            try {
                Attachments.saveBookmarks(activity, name, marks);
            } catch (IOException failure) {
                Toast.makeText(activity, "Bookmarks were not saved.", Toast.LENGTH_SHORT).show();
            }
            done.accept(name);
        };
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Recording")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Stop", (d, w) -> stop.run())
                .show();
    }

    /** Plays a recording with a seek bar and its bookmarks. */
    static void play(Activity activity, String name) {
        File file = Attachments.file(activity, name);
        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(file.getPath());
            player.prepare();
        } catch (IOException | RuntimeException failure) {
            player.release();
            Toast.makeText(activity, "This recording cannot be played.", Toast.LENGTH_LONG).show();
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        LinearLayout box = column(activity);
        TextView clock = big(activity, "0:00 / " + Attachments.time(player.getDuration()));
        box.addView(clock);
        SeekBar seek = new SeekBar(activity);
        seek.setMax(player.getDuration());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user) player.seekTo(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        box.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        MaterialButton playPause = new MaterialButton(activity);
        playPause.setText("Play");
        playPause.setOnClickListener(v -> {
            if (player.isPlaying()) player.pause(); else player.start();
            playPause.setText(player.isPlaying() ? "Pause" : "Play");
        });
        box.addView(playPause);
        player.setOnCompletionListener(p -> playPause.setText("Play"));

        List<Long> marks = Attachments.bookmarks(activity, name);
        if (!marks.isEmpty()) box.addView(small(activity, "Bookmarks"));
        for (int i = 0; i < marks.size(); i++) {
            long mark = marks.get(i);
            MaterialButton jump = new MaterialButton(activity, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            jump.setText((i + 1) + "  ·  " + Attachments.time(mark));
            jump.setOnClickListener(v -> {
                player.seekTo((int) mark);
                if (!player.isPlaying()) player.start();
                playPause.setText("Pause");
            });
            box.addView(jump);
        }
        Runnable tick = new Runnable() {
            @Override public void run() {
                seek.setProgress(player.getCurrentPosition());
                clock.setText(Attachments.time(player.getCurrentPosition()) + " / "
                        + Attachments.time(player.getDuration()));
                handler.postDelayed(this, 300);
            }
        };
        handler.post(tick);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Recording")
                .setView(box)
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> {
                    handler.removeCallbacks(tick);
                    player.release();
                })
                .show();
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        return box;
    }

    private static TextView big(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(32);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static TextView small(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 16, 0, 16);
        return view;
    }
}
