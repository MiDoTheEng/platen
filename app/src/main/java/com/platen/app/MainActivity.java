package com.platen.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

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
        Typeface plain;
        try {
            plain = Typeface.createFromAsset(getAssets(), "fonts/courier10.otf");
        } catch (Exception e) {
            plain = Typeface.MONOSPACE;
        }
        for (int k = 0; k < files.length; k++) {
            out[k] = plain;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    Font courier = new Font.Builder(getAssets(), "fonts/courier10.otf").build();
                    Font arabic = new Font.Builder(getAssets(), "fonts/" + files[k]).build();
                    out[k] = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(courier).build())
                            .addCustomFallback(new FontFamily.Builder(arabic).build())
                            .setSystemFallback("monospace")
                            .build();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
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
                .setItems(new String[]{"As text", "As an image of the page", "Copy text"}, (d, which) -> {
                    if (which == 0) shareText();
                    else if (which == 1) shareImage();
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
