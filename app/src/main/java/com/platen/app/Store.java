package com.platen.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pages are kept as one small JSON file each, in the app's private storage. */
final class Store {

    static final class Note {
        String id;
        String text = "";
        long created;
        long updated;
    }

    private final File dir;

    Store(Context c) {
        dir = new File(c.getFilesDir(), "pages");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    List<Note> all() {
        List<Note> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = new JSONObject(read(f));
                Note n = new Note();
                n.id = o.getString("id");
                n.text = o.optString("text", "");
                n.created = o.optLong("created");
                n.updated = o.optLong("updated");
                out.add(n);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updated, a.updated));
        return out;
    }

    Note create() {
        Note n = new Note();
        long now = System.currentTimeMillis();
        n.id = Long.toString(now, 36);
        n.created = now;
        n.updated = now;
        return n;
    }

    void save(Note n) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", n.id);
            o.put("text", n.text);
            o.put("created", n.created);
            o.put("updated", n.updated);
            File tmp = new File(dir, n.id + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) {
                os.write(o.toString().getBytes(StandardCharsets.UTF_8));
                os.getFD().sync();
            }
            File dst = new File(dir, n.id + ".json");
            if (!tmp.renameTo(dst)) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(dst);
            }
        } catch (Exception ignored) {
        }
    }

    void delete(Note n) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, n.id + ".json").delete();
    }

    private static String read(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
