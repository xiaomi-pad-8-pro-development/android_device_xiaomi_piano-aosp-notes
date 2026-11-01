package com.alxp.pianonotes;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Handwriting to text with MyScript iink, as Xiaomi Notes does. The SDK, its
 * recognition assets and the developer certificate are private and only in
 * personal builds (see myscript/README.md); without them this reports that
 * conversion is unavailable. Called through reflection so the app builds
 * either way.
 */
final class HandwritingText {
    private static final String ASSETS = "recognition-assets";
    private static Object engine;

    private HandwritingText() {}

    static boolean available() {
        try {
            Class.forName("com.myscript.iink.Engine");
            Class.forName("com.myscript.certificate.MyCertificate");
            return true;
        } catch (ClassNotFoundException missing) {
            return false;
        }
    }

    /** Recognises the strokes as English text. Call off the main thread. */
    static String recognize(Context context, List<List<float[]>> strokes) throws Exception {
        if (strokes.isEmpty()) return "";
        Object recognizer = null;
        try {
            Object iink = engine(context);
            float pixelsPerMm = context.getResources().getDisplayMetrics().xdpi / 25.4f;
            recognizer = iink.getClass().getMethod("createRecognizer", float.class, float.class,
                    String.class).invoke(iink, pixelsPerMm, pixelsPerMm, "Text");
            Class<?> type = recognizer.getClass();
            Method down = type.getMethod("pointerDown", float.class, float.class, long.class, float.class);
            Method move = type.getMethod("pointerMove", float.class, float.class, long.class, float.class);
            Method up = type.getMethod("pointerUp", float.class, float.class, long.class, float.class);
            // Strokes are stored without times; space the points evenly.
            long time = System.currentTimeMillis() - 60_000;
            for (List<float[]> stroke : strokes) {
                for (int i = 0; i < stroke.size(); i++) {
                    float[] p = stroke.get(i);
                    float pressure = p.length > 2 && p[2] > 0 ? Math.min(1f, p[2]) : 0.5f;
                    Method event = i == 0 ? down : i == stroke.size() - 1 ? up : move;
                    event.invoke(recognizer, p[0], p[1], time, pressure);
                    time += 8;
                }
                if (stroke.size() == 1) up.invoke(recognizer, stroke.get(0)[0], stroke.get(0)[1], time, 0.5f);
                time += 120;
            }
            type.getMethod("waitForIdle").invoke(recognizer);
            Class<?> mime = Class.forName("com.myscript.iink.MimeType");
            Object text = mime.getField("TEXT").get(null);
            String result = (String) type.getMethod("getResult", mime).invoke(recognizer, text);
            return result == null ? "" : result.trim();
        } catch (java.lang.reflect.InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            throw cause instanceof Exception ? (Exception) cause : failure;
        } finally {
            if (recognizer != null) recognizer.getClass().getMethod("close").invoke(recognizer);
        }
    }

    private static synchronized Object engine(Context context) throws Exception {
        if (engine != null) return engine;
        File assets = new File(context.getFilesDir(), ASSETS);
        copyAssets(context.getAssets(), ASSETS, assets);
        byte[] certificate = (byte[]) Class.forName("com.myscript.certificate.MyCertificate")
                .getMethod("getBytes").invoke(null);
        Object created = Class.forName("com.myscript.iink.Engine")
                .getMethod("create", byte[].class).invoke(null, (Object) certificate);
        Object configuration = created.getClass().getMethod("getConfiguration").invoke(created);
        Class<?> config = configuration.getClass();
        config.getMethod("setStringArray", String.class, String[].class).invoke(configuration,
                "configuration-manager.search-path",
                (Object) new String[]{new File(assets, "conf").getPath()});
        config.getMethod("setString", String.class, String.class).invoke(configuration,
                "content-package.temp-folder", new File(context.getCacheDir(), "iink").getPath());
        config.getMethod("setString", String.class, String.class).invoke(configuration,
                "lang", "en_US");
        engine = created;
        return engine;
    }

    /** Unpacks the recognition assets from the APK once per app version. */
    private static void copyAssets(AssetManager manager, String path, File target) throws IOException {
        String[] children = manager.list(path);
        if (children == null || children.length == 0) {
            if (target.exists()) return;
            target.getParentFile().mkdirs();
            try (InputStream in = manager.open(path); OutputStream out = new FileOutputStream(target)) {
                in.transferTo(out);
            }
            return;
        }
        target.mkdirs();
        for (String child : children) {
            copyAssets(manager, path + "/" + child, new File(target, child));
        }
    }
}
