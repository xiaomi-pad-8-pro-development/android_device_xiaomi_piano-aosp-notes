package com.alxp.pianonotes;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.ArrayList;
import java.util.List;

/** Versioned ink container. Legacy arrays always represent one page. */
final class InkDocument {
    static final int MAX_PAGES = 256;
    private InkDocument() {}

    static List<JSONArray> read(String json) throws JSONException {
        JSONTokener tokener = new JSONTokener(json);
        Object root = tokener.nextValue();
        if (tokener.nextClean() != 0) throw new JSONException("Trailing ink data");
        List<JSONArray> result = new ArrayList<>();
        if (root instanceof JSONArray) {
            result.add((JSONArray) root);
            return result;
        }
        if (!(root instanceof JSONObject)) throw new JSONException("Invalid ink document");
        JSONObject object = (JSONObject) root;
        if (!"alxp-ink".equals(object.getString("format")) || !(object.get("version") instanceof Number)
                || object.getDouble("version") != 1d)
            throw new JSONException("Unsupported ink document version");
        JSONArray pages = object.getJSONArray("pages");
        if (pages.length() < 1 || pages.length() > MAX_PAGES)
            throw new JSONException("Invalid ink page count");
        for (int i = 0; i < pages.length(); i++) result.add(pages.getJSONArray(i));
        return result;
    }

    static String write(List<JSONArray> pages) throws JSONException {
        if (pages.isEmpty() || pages.size() > MAX_PAGES)
            throw new JSONException("Invalid ink page count");
        for (JSONArray page : pages)
            if (page == null) throw new JSONException("Missing ink page");
        // Keep unchanged single-page documents readable by older app versions.
        if (pages.size() == 1) return pages.get(0).toString();
        JSONArray array = new JSONArray();
        for (JSONArray page : pages) {
            if (page == null) throw new JSONException("Missing ink page");
            array.put(page);
        }
        return new JSONObject().put("format", "alxp-ink").put("version", 1)
                .put("pages", array).toString();
    }
}
