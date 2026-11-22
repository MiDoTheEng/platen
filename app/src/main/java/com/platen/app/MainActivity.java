package com.platen.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements TypewriterView.Host {

    private Store store;
    private Sounds sounds;
    private SharedPreferences prefs;
    private Typeface[] faces;
    private TypewriterView editor;
    private Store.Note current;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saver = this::saveNow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        prefs = getSharedPreferences("platen", MODE_PRIVATE);
        sounds = new Sounds(this);
        faces = loadFaces();
        showHome();
    }

    /**
     * Courier 10 Pitch for Latin letters, with an Arabic face as the fallback for Arabic:
     * Amiri, Aref Ruqaa or Amiri Typewriter (all SIL Open Font License).
     */
    private Typeface[] loadFaces() {
        String[] files = TypewriterView.ARABIC_FONT_FILES;
        Typeface[] out = new Typeface[files.length];
        File custom = customFontFile();
        boolean useCustom = prefs.getInt("latin", 0) == 1 && custom.exists()
                && prefs.getString("customFontName", null) != null;
        Typeface plain;
        try {
            plain = useCustom ? Typeface.createFromFile(custom)
                    : Typeface.createFromAsset(getAssets(), "fonts/courier10.otf");
        } catch (Exception e) {
            plain = Typeface.MONOSPACE;
        }
        for (int k = 0; k < files.length; k++) {
            out[k] = plain;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    Font latin = useCustom ? new Font.Builder(custom).build()
                            : new Font.Builder(getAssets(), "fonts/courier10.otf").build();
                    Font arabic = new Font.Builder(getAssets(), "fonts/" + files[k]).build();
                    out[k] = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(latin).build())
                            .addCustomFallback(new FontFamily.Builder(arabic).build())
                            .setSystemFallback("monospace")
                            .build();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private File customFontFile() {
        File d = new File(getFilesDir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, "custom-font");
    }

    // ---------------------------------------------------------------- importing a font

    private static final int PICK_FONT = 42;

    @Override
    public void onFontsChanged() {
        faces = loadFaces();
        if (editor != null) editor.setFaces(faces);
    }

    @Override
    public void onImportFont() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK_FONT);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_FONT || res != RESULT_OK || data == null || data.getData() == null) return;
        importFont(data.getData());
    }

    /** Copies a .ttf / .otf (or the first one inside a .zip, as font sites hand them out) into the app. */
    private void importFont(Uri uri) {
        String name = "Imported font";
        try (android.database.Cursor cur = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) name = cur.getString(0);
        } catch (Exception ignored) {
        }
        File tmp = new File(getCacheDir(), "font-import");
        try {
            byte[] head = new byte[4];
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException();
                int got = in.read(head);
                if (got < 4) throw new IllegalStateException();
            }
            boolean zip = head[0] == 'P' && head[1] == 'K';
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(tmp)) {
                if (in == null) throw new IllegalStateException();
                if (zip) {
                    java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(in);
                    java.util.zip.ZipEntry e;
                    boolean found = false;
                    while ((e = z.getNextEntry()) != null) {
                        String n = e.getName().toLowerCase(Locale.ROOT);
                        if (e.isDirectory() || n.contains("__macosx")) continue;
                        if (n.endsWith(".otf") || n.endsWith(".ttf")) {
                            copy(z, os);
                            String base = e.getName();
                            int slash = base.lastIndexOf('/');
                            name = slash >= 0 ? base.substring(slash + 1) : base;
                            found = true;
                            break;
                        }
                    }
                    if (!found) throw new IllegalStateException("no font in zip");
                } else {
                    copy(in, os);
                }
            }
            // make sure it really is a font before using it
            if (Build.VERSION.SDK_INT >= 29) new Font.Builder(tmp).build();
            else if (Typeface.createFromFile(tmp) == null) throw new IllegalStateException();

            File dst = customFontFile();
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            if (!tmp.renameTo(dst)) throw new IllegalStateException();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            prefs.edit().putString("customFontName", name).putInt("latin", 1).apply();
            onFontsChanged();
            Toast.makeText(this, "English text now uses " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Toast.makeText(this, "That file isn't a font Platen can use (.ttf, .otf or a .zip with one)", Toast.LENGTH_LONG).show();
        }
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream os) throws java.io.IOException {
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
    }

    private Typeface currentFace() {
        int k = Math.max(0, Math.min(faces.length - 1, prefs.getInt("arFont", 0)));
        return faces[k];
    }

    // ---------------------------------------------------------------- screens

    private void showHome() {
        current = null;
        editor = null;
        int paper = TypewriterView.TONES[Math.max(0, Math.min(TypewriterView.TONES.length - 1, prefs.getInt("tone", 0)))];
        setContentView(new HomeView(this, store.all(), paper, currentFace(), new HomeView.Host() {
            @Override
            public void open(Store.Note n) {
                openNote(n);
            }

            @Override
            public void create() {
                openNote(store.create());
            }

            @Override
            public void clearAll() {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Clear all pages?")
                        .setMessage("Every page will be deleted. This can't be undone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear all", (d, w) -> {
                            for (Store.Note n : store.all()) store.delete(n);
                            showHome();
                        })
                        .show();
            }

            @Override
            public void delete(Store.Note n) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete this page?")
                        .setMessage("It can't be brought back.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> {
                            store.delete(n);
                            showHome();
                        })
                        .show();
            }
        }));
    }

    private void openNote(Store.Note n) {
        current = n;
        editor = new TypewriterView(this, sounds, prefs, faces, this);
        editor.setText(n.text);
        setContentView(editor);
        editor.postDelayed(editor::showKeyboard, 150);
    }

    private void leaveEditor() {
        if (editor != null) editor.hideKeyboard();
        handler.removeCallbacks(saver);
        saveNow();
        showHome();
    }

    private void saveNow() {
        if (current == null) return;
        if (current.text.trim().isEmpty()) store.delete(current);
        else store.save(current);
    }

    // ---------------------------------------------------------------- editor callbacks

    @Override
    public void onTextChanged(String text) {
        if (current == null) return;
        current.text = text;
        current.updated = System.currentTimeMillis();
        handler.removeCallbacks(saver);
        handler.postDelayed(saver, 600);
    }

    @Override
    public void onBack() {
        leaveEditor();
    }

    @Override
    public void onShare() {
        if (editor == null || current == null || current.text.trim().isEmpty()) {
            Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Share page")
                .setItems(new String[]{"As text", "As an image of the page", "Save image to gallery", "Copy text"}, (d, which) -> {
                    if (which == 0) shareText();
                    else if (which == 1) shareImage();
                    else if (which == 2) saveToGallery();
                    else copyText();
                })
                .show();
    }

    private void shareText() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, current.text);
        startActivity(Intent.createChooser(i, "Share page"));
    }

    private void shareImage() {
        try {
            Bitmap b = editor.renderPage(1080);
            File dir = ShareProvider.dir(this);
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            File f = new File(dir, "page-" + System.currentTimeMillis() + ".png");
            try (FileOutputStream os = new FileOutputStream(f)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            b.recycle();
            Uri u = ShareProvider.uriFor(f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/png");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(ClipData.newRawUri("page", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share page"));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't make the image", Toast.LENGTH_SHORT).show();
        }
    }

    /** Saves the page image into Pictures/Platen, where the gallery shows it as an album. */
    private void saveToGallery() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Saving needs Android 10 or newer; use Share instead", Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap b = null;
        Uri item = null;
        ContentResolver cr = getContentResolver();
        try {
            b = editor.renderPage(1080);
            String name = "Platen " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date()) + ".png";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Platen");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            item = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (item == null) throw new IllegalStateException("no gallery entry");
            try (OutputStream os = cr.openOutputStream(item)) {
                if (os == null || !b.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new IllegalStateException("write failed");
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(item, v, null, null);
            Toast.makeText(this, "Saved to gallery · Pictures/Platen", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (item != null) {
                try {
                    cr.delete(item, null, null);
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "Couldn't save the image", Toast.LENGTH_SHORT).show();
        } finally {
            if (b != null) b.recycle();
        }
    }

    private void copyText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Platen page", current.text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- lifecycle

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (editor != null) leaveEditor();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(saver);
        saveNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sounds.release();
    }
}
