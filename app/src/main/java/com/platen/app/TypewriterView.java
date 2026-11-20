package com.platen.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.text.Bidi;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Random;

/**
 * The whole writing screen, drawn by hand: a sheet of paper that feeds upward line by line,
 * the platen bar with its carriage pill, a type bar that strikes each letter, ink-stamped
 * characters, the lamp (dark) mode with a cone of light, the top bar and the tool pill.
 * The soft keyboard types straight into this view.
 */
@SuppressLint("ViewConstructor")
public class TypewriterView extends View {

    public interface Host {
        void onTextChanged(String text);

        void onBack();

        void onShare();
    }

    static final int LIMIT = 3000;

    static final int[] TONES = {0xFFEBDADF, 0xFFF1E8D6, 0xFFF6F4EE, 0xFFDCE3EA};
    static final String[] TONE_NAMES = {"Rose paper", "Cream paper", "White paper", "Blue paper"};
    static final float[] SIZES = {13f, 15f, 18f};
    static final String[] SIZE_NAMES = {"Small type", "Medium type", "Large type"};
    static final String[] ARABIC_FONT_FILES = {"amiri.ttf", "aref_ruqaa.ttf", "amiri_typewriter.ttf"};
    static final String[] ARABIC_FONT_NAMES = {"Amiri", "Aref Ruqaa", "Amiri Typewriter"};

    private static final int INK = 0xFF231E21;
    private static final int TOP_BG = 0xFF0F0E11;

    private final Host host;
    private final Sounds sounds;
    private final SharedPreferences prefs;
    private final float dp, sp;
    private final Typeface[] faces;
    private Typeface face;
    private int arFont;
    private final InputMethodManager imm;
    private final int touchSlop;

    // --- text and per-letter ink stamps
    private SpannableStringBuilder text = new SpannableStringBuilder();
    private final ArrayList<Stamp> stamps = new ArrayList<>();
    private final Watcher watcher = new Watcher();

    // --- layout (monospaced grid)
    // Each character's line, left edge, width and direction. Lines may run left-to-right or
    // right-to-left (Arabic); Arabic words are drawn as whole runs so their letters stay joined.
    private int[] lineOf = new int[0];
    private float[] xOf = new float[0], wOf = new float[0], tmpW = new float[64];
    private boolean[] rtlOf = new boolean[0], lineRtl = new boolean[4];
    private final ArrayList<Run> runs = new ArrayList<>();
    private int cursorLine, cols = 30;      // last line of the text
    private float textW;
    private int caretLine;                  // where the next letter goes
    private float caretX;
    private boolean caretRtl;
    private boolean kbRtl;                  // the keyboard is set to a right-to-left language
    private long kbCheckedAt;
    private int posLine;
    private float posX;
    private boolean posRtl;

    private static final class Run {
        int s, e, line;
        float x;
    }
    private float lastBase0, lastTextL;
    private int pendIns, pendRem;
    private char pendLast;
    private int arrowHeld = 0;
    private long caretMovedAt = 0;
    private boolean arrowRepeated;
    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (arrowHeld == 0) return;
            arrowRepeated = true;
            moveCaret(arrowHeld);
            postDelayed(this, 60);
        }
    };
    private float charW, fontPx, lineH, marginX, paperW;

    // --- animation state
    private float animLine = 0, headX = -1, scrollBack = 0;
    private boolean scrollReturn = false;
    private long strikeAt = 0, lastBorn = 0, toastAt = 0;
    private String toast = "";
    private int bellLine = -1;

    // --- settings
    private boolean lamp, sound;
    private int tone, sizeIdx;

    // --- touch
    private float downX, downY, dragStart;
    private boolean dragging;
    private boolean kbShown;

    // --- paints and shapes
    private final TextPaint ink = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix grainMatrix = new Matrix();
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final RectF backHit = new RectF(), shareHit = new RectF();
    private final RectF[] toolHits = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(),
            new RectF(), new RectF(), new RectF()};
    private final NumberFormat nf = NumberFormat.getIntegerInstance();

    public TypewriterView(Context c, Sounds sounds, SharedPreferences prefs, Typeface[] faces, Host host) {
        super(c);
        this.host = host;
        this.sounds = sounds;
        this.prefs = prefs;
        this.faces = faces;
        arFont = Math.max(0, Math.min(faces.length - 1, prefs.getInt("arFont", 0)));
        this.face = faces[arFont];
        dp = getResources().getDisplayMetrics().density;
        sp = dp * getResources().getConfiguration().fontScale;
        imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        kbRtl = detectKbRtl();

        lamp = prefs.getBoolean("lamp", false);
        sound = prefs.getBoolean("sound", true);
        tone = Math.max(0, Math.min(TONES.length - 1, prefs.getInt("tone", 0)));
        sizeIdx = Math.max(0, Math.min(SIZES.length - 1, prefs.getInt("size", 1)));

        setFocusable(true);
        setFocusableInTouchMode(true);
        setHapticFeedbackEnabled(true);

        ink.setTypeface(face);
        ink.setColor(INK);
        label.setTypeface(face);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        grainPaint.setShader(new BitmapShader(makeGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));

        applySize();
        setText("");
    }

    // ================================================================ public API

    void setText(String s) {
        text = new SpannableStringBuilder(s == null ? "" : s);
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LIMIT)});
        stamps.clear();
        for (int i = 0; i < text.length(); i++) stamps.add(makeStamp(i, text.charAt(i), 0));
        text.setSpan(watcher, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        Selection.setSelection(text, text.length());
        relayout();
        animLine = caretLine;
        headX = -1;
        scrollBack = 0;
        if (imm != null) imm.restartInput(this);
        invalidate();
    }

    String getText() {
        return text.toString();
    }

    void showKeyboard() {
        requestFocus();
        if (imm != null) imm.showSoftInput(this, 0);
        kbShown = true;
    }

    void hideKeyboard() {
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        kbShown = false;
    }

    /** The page as a picture, for sharing. */
    Bitmap renderPage(int outW) {
        float top = 44 * dp, bottom = 52 * dp;
        float contentH = top + Math.max(1, cursorLine + 1) * lineH + bottom;
        float scale = outW / paperW;
        Bitmap b = Bitmap.createBitmap(outW, (int) Math.ceil(contentH * scale), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(scale, scale);
        c.drawColor(TONES[tone]);
        grainMatrix.reset();
        grainPaint.getShader().setLocalMatrix(grainMatrix);
        c.drawRect(0, 0, paperW, contentH, grainPaint);
        float base0 = top + fontPx * 0.8f;
        drawLetters(c, marginX, base0, -1e9f, 1e9f, 0, false);
        if (lamp) drawLampOnImage(c, base0, contentH);
        return b;
    }

    /** Lamp mode in a shared image: the bar and carriage under the last line, lit from there. */
    private void drawLampOnImage(Canvas c, float base0, float contentH) {
        float barY = Math.min(contentH - 14 * dp, base0 + cursorLine * lineH + 18 * dp);
        pos(text.length());
        float hx = marginX + posX + (posRtl ? -charW / 2f : charW / 2f);
        float savedHead = headX;
        headX = hx;
        int W = (int) Math.ceil(paperW), H = (int) Math.ceil(contentH);

        float bt = 4.5f * dp;
        fill.setShader(new LinearGradient(0, barY - bt, 0, barY + bt,
                new int[]{0xFF4A464E, 0xFF1A181C, 0xFF0B0A0C, 0xFF2C2A30},
                new float[]{0f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        r.set(0, barY - bt, paperW, barY + bt);
        c.drawRect(r, fill);
        fill.setShader(null);
        r.set(hx - 25 * dp, barY - 10 * dp, hx + 25 * dp, barY + 10 * dp);
        fill.setColor(0xFF2B2629);
        c.drawRoundRect(r, 10 * dp, 10 * dp, fill);
        int[] dots = {0xFFFFB347, 0xFFFF6B4A, 0xFFFFB347};
        for (int i = 0; i < 3; i++) {
            fill.setColor(dots[i]);
            c.drawCircle(hx - 12 * dp + i * 6 * dp, barY, 1.9f * dp, fill);
        }

        // reach a little past the top so the first lines of a long page are still readable
        drawLamp(c, W, H, barY, paperW, Math.max(barY * 1.25f, 260 * dp));
        headX = savedHead;
    }

    // ================================================================ keyboard input

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo out) {
        out.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_ENTER_ACTION
                | EditorInfo.IME_ACTION_NONE;
        out.initialSelStart = text.length();
        out.initialSelEnd = text.length();
        return new Conn();
    }

    private final class Conn extends BaseInputConnection {
        Conn() {
            super(TypewriterView.this, true);
        }

        @Override
        public Editable getEditable() {
            return text;
        }

        @Override
        public boolean performContextMenuAction(int id) {
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                paste();
                return true;
            }
            if (id == android.R.id.copy) {
                copyAll();
                return true;
            }
            return super.performContextMenuAction(id);
        }

        @Override
        public boolean setSelection(int start, int end) {
            boolean ok = super.setSelection(start, end);
            post(TypewriterView.this::onCaretMoved);
            return ok;
        }
    }

    private int selStart() {
        int s = Selection.getSelectionStart(text);
        return s < 0 ? text.length() : Math.min(s, text.length());
    }

    private int caret() {
        int e = Selection.getSelectionEnd(text);
        return e < 0 ? text.length() : Math.min(e, text.length());
    }

    /** Types at the cursor, replacing any selected text. */
    private void typeAtCaret(String s) {
        int a = Math.min(selStart(), caret()), b = Math.max(selStart(), caret());
        text.replace(a, b, s);
        int p = Math.min(a + s.length(), text.length());
        Selection.setSelection(text, p);
        onCaretMoved();
    }

    private void onCaretMoved() {
        caretMovedAt = System.currentTimeMillis();
        updateCaret();
        scrollReturn = true;
        reportSelection();
        invalidate();
    }

    /** dir: -1 left, +1 right, -2 up, +2 down. */
    private void moveCaret(int dir) {
        int n = text.length();
        int p = caret();
        if (dir == -1 || dir == 1) {
            // arrows move on screen: in a right-to-left line, the right arrow goes back
            int logical = (dir == 1) != caretRtl ? 1 : -1;
            if (logical < 0) {
                if (p > 0) p -= Character.charCount(Character.codePointBefore(text, p));
            } else {
                if (p < n) p += Character.charCount(Character.codePointAt(text, p));
            }
        } else {
            int target = caretLine + (dir > 0 ? 1 : -1);
            if (target < 0) p = 0;
            else if (target > cursorLine) p = n;
            else p = indexAtX(target, caretX);
        }
        Selection.setSelection(text, p);
        onCaretMoved();
    }

    /** The cursor position on a line closest to an x position. */
    private int indexAtX(int line, float x) {
        int n = text.length();
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int p = 0; p <= n; p++) {
            if (p < n && Character.isLowSurrogate(text.charAt(p))) continue;
            pos(p);
            if (posLine != line) continue;
            float d = Math.abs(posX - x);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best < 0 ? caret() : best;
    }

    /** Where a cursor at index p sits: line, x, and which way the next letter goes. */
    private void pos(int p) {
        int n = text.length();
        boolean atBreak = p >= n || text.charAt(p) == '\n';
        int prev = -1;
        if (p > 0) {
            int q = p - Character.charCount(Character.codePointBefore(text, p));
            if (text.charAt(q) != '\n') prev = q;
        }
        if (prev >= 0 && (atBreak || lineOf[prev] == lineOf[p])) {
            posLine = lineOf[prev];
            posRtl = rtlOf[prev];
            posX = posRtl ? xOf[prev] : xOf[prev] + wOf[prev];
        } else if (!atBreak) {
            posLine = lineOf[p];
            posRtl = rtlOf[p];
            posX = posRtl ? xOf[p] + wOf[p] : xOf[p];
        } else {
            posLine = p < n ? lineOf[p] : cursorLine;
            posRtl = posLine < lineRtl.length && lineRtl[posLine];
            posX = posRtl ? textW : 0;
        }
    }

    private void updateCaret() {
        pos(caret());
        caretLine = posLine;
        caretX = posX;
        caretRtl = posRtl;
    }

    private boolean detectKbRtl() {
        try {
            if (imm == null) return false;
            InputMethodSubtype st = imm.getCurrentInputMethodSubtype();
            if (st == null) return false;
            String tag = st.getLanguageTag();
            if (tag == null || tag.isEmpty()) tag = st.getLocale();
            if (tag == null) return false;
            tag = tag.toLowerCase(java.util.Locale.ROOT);
            return tag.startsWith("ar") || tag.startsWith("fa") || tag.startsWith("ur")
                    || tag.startsWith("he") || tag.startsWith("iw") || tag.startsWith("ps") || tag.startsWith("ckb");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveCaret(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveCaret(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCaret(-2);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCaret(2);
                return true;
            case KeyEvent.KEYCODE_MOVE_HOME:
                Selection.setSelection(text, indexAtX(caretLine, lineRtl[caretLine] ? textW : 0));
                onCaretMoved();
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                Selection.setSelection(text, indexAtX(caretLine, lineRtl[caretLine] ? 0 : textW));
                onCaretMoved();
                return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            int a = Math.min(selStart(), caret()), b = Math.max(selStart(), caret());
            if (a != b) text.delete(a, b);
            else if (b > 0) text.delete(b - Character.charCount(Character.codePointBefore(text, b)), b);
            onCaretMoved();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            int p = caret();
            if (p < text.length()) text.delete(p, p + Character.charCount(Character.codePointAt(text, p)));
            onCaretMoved();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            typeAtCaret("\n");
            return true;
        }
        int u = e.getUnicodeChar(e.getMetaState());
        if (u > 0 && !Character.isISOControl(u)) {
            typeAtCaret(new String(Character.toChars(u)));
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private final class Watcher implements TextWatcher {
        private String oldSeg = "";

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            oldSeg = s.subSequence(start, start + count).toString();
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // Keyboards often rewrite the whole word being composed on each key press;
            // keep the letters that did not change so they are not stamped again.
            String newSeg = s.subSequence(start, start + count).toString();
            int p = 0;
            int lim = Math.min(oldSeg.length(), newSeg.length());
            while (p < lim && oldSeg.charAt(p) == newSeg.charAt(p)) p++;
            int from = start + p;
            for (int i = 0; i < before - p && from < stamps.size(); i++) stamps.remove(from);
            long now = System.currentTimeMillis();
            for (int i = p; i < count; i++) stamps.add(start + i, makeStamp(start + i, s.charAt(start + i), now));
            pendIns = count - p;
            pendRem = before - p;
            pendLast = count > p ? s.charAt(start + count - 1) : 0;
            relayout();
        }

        @Override
        public void afterTextChanged(Editable s) {
            onEdit(pendIns, pendRem, pendLast);
            post(TypewriterView.this::reportSelection);
            host.onTextChanged(s.toString());
        }
    }

    private void reportSelection() {
        if (imm == null) return;
        imm.updateSelection(this, selStart(), caret(),
                BaseInputConnection.getComposingSpanStart(text),
                BaseInputConnection.getComposingSpanEnd(text));
    }

    private void onEdit(int inserted, int removed, char last) {
        int prevLine = caretLine;
        caretMovedAt = System.currentTimeMillis();
        updateCaret();
        scrollReturn = true;
        if (inserted > 0) {
            lastBorn = System.currentTimeMillis();
            if (last == '\n') {
                if (sound) sounds.carriageReturn();
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } else {
                strikeAt = lastBorn;
                if (sound) {
                    if (last == ' ') sounds.space();
                    else sounds.key();
                }
                if (caretLine != prevLine && sound) sounds.carriageReturn();
            }
            float room = caretRtl ? caretX : textW - caretX;
            if (room < 6 * charW && room > 0.5f * charW && bellLine != caretLine && last != '\n') {
                bellLine = caretLine;
                if (sound) sounds.bell();
            }
            if (text.length() >= LIMIT) showToast("Page full · 3,000 characters");
        } else if (removed > 0) {
            if (sound) sounds.back();
        }
        if (bellLine > caretLine) bellLine = -1;
        invalidate();
    }

    // ================================================================ layout

    private void applySize() {
        fontPx = SIZES[sizeIdx] * sp;
        ink.setTextSize(fontPx);
        charW = ink.measureText("M");
        lineH = fontPx * 1.5f;
        marginX = 24 * dp;
        computeCols(getWidth());
    }

    private void computeCols(int w) {
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        float avail = Math.min(w - 2 * 18 * dp, 560 * dp) - 2 * marginX;
        cols = Math.max(12, (int) Math.floor(avail / charW));
        paperW = cols * charW + 2 * marginX;
        relayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeCols(w);
        animLine = caretLine;
        headX = -1;
    }

    /**
     * Lays the text out: line breaks from a StaticLayout, then each line split into
     * left-to-right and right-to-left runs (java.text.Bidi) and placed on the page.
     * A paragraph's direction comes from its first letter; an empty one follows the keyboard.
     */
    private void relayout() {
        String s = text.toString();
        int n = s.length();
        if (lineOf.length < n + 1) {
            int cap = n + 64;
            lineOf = new int[cap];
            xOf = new float[cap];
            wOf = new float[cap];
            rtlOf = new boolean[cap];
        }
        runs.clear();
        textW = cols * charW;
        StaticLayout lay = StaticLayout.Builder.obtain(s, 0, n, ink, Math.max(1, (int) Math.ceil(textW)))
                .setTextDirection(kbRtl ? TextDirectionHeuristics.FIRSTSTRONG_RTL : TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
        int lines = lay.getLineCount();
        if (lineRtl.length < lines) lineRtl = new boolean[lines + 16];
        for (int L = 0; L < lines; L++) {
            int ls = lay.getLineStart(L), le = lay.getLineEnd(L);
            int ce = le;
            if (ce > ls && s.charAt(ce - 1) == '\n') ce--;
            boolean prtl = lay.getParagraphDirection(L) == Layout.DIR_RIGHT_TO_LEFT;
            lineRtl[L] = prtl;
            for (int i = ls; i < le; i++) {
                lineOf[i] = L;
                xOf[i] = prtl ? textW : 0;
                wOf[i] = 0;
                rtlOf[i] = prtl;
            }
            if (ce <= ls) continue;
            Bidi bidi = new Bidi(s.substring(ls, ce), prtl ? Bidi.DIRECTION_RIGHT_TO_LEFT : Bidi.DIRECTION_LEFT_TO_RIGHT);
            int rc = bidi.getRunCount();
            byte[] levels = new byte[rc];
            Integer[] order = new Integer[rc];
            for (int k = 0; k < rc; k++) {
                levels[k] = (byte) bidi.getRunLevel(k);
                order[k] = k;
            }
            Bidi.reorderVisually(levels, 0, order, 0, rc);
            float x = 0;
            int firstRun = runs.size();
            for (int v = 0; v < rc; v++) {
                int k = order[v];
                int rs = ls + bidi.getRunStart(k), re = ls + bidi.getRunLimit(k);
                boolean rtl = (bidi.getRunLevel(k) & 1) == 1;
                if (!rtl) {
                    if (tmpW.length < re - rs) tmpW = new float[re - rs + 32];
                    ink.getTextWidths(s, rs, re, tmpW);
                    for (int i = rs; i < re; i++) {
                        xOf[i] = x;
                        wOf[i] = tmpW[i - rs];
                        rtlOf[i] = false;
                        x += tmpW[i - rs];
                    }
                } else {
                    float rw = ink.getRunAdvance(s, rs, re, rs, re, true, re);
                    for (int i = rs; i < re; i++) {
                        rtlOf[i] = true;
                        if (i > rs && Character.isLowSurrogate(s.charAt(i))) {
                            xOf[i] = xOf[i - 1];
                            wOf[i] = 0;
                            continue;
                        }
                        int j = (Character.isHighSurrogate(s.charAt(i)) && i + 1 < re) ? i + 2 : i + 1;
                        float a0 = ink.getRunAdvance(s, rs, re, rs, re, true, i);
                        float a1 = ink.getRunAdvance(s, rs, re, rs, re, true, j);
                        xOf[i] = x + rw - a1;
                        wOf[i] = a1 - a0;
                    }
                    Run run = new Run();
                    run.s = rs;
                    run.e = re;
                    run.line = L;
                    run.x = x;
                    runs.add(run);
                    x += rw;
                }
            }
            if (prtl) { // right-to-left lines sit against the right margin
                float off = textW - x;
                for (int i = ls; i < ce; i++) xOf[i] += off;
                for (int q = firstRun; q < runs.size(); q++) runs.get(q).x += off;
            }
        }
        cursorLine = lines - 1;
        updateCaret();
    }

    // ================================================================ stamps

    private static final class Stamp {
        float dx, dy, rot, alpha;
        boolean dbl, splat;
        long seed, born;
    }

    private Stamp makeStamp(int index, char c, long born) {
        Random rr = new Random(index * 1000003L + c * 7919L + 17);
        Stamp s = new Stamp();
        s.dx = (rr.nextFloat() - 0.5f) * 0.9f * dp;
        s.dy = (rr.nextFloat() - 0.5f) * 1.1f * dp;
        s.rot = (rr.nextFloat() - 0.5f) * 3.2f;
        s.alpha = 0.70f + rr.nextFloat() * 0.30f;
        s.dbl = rr.nextFloat() < 0.18f;
        boolean spl = rr.nextFloat() < 0.035f;
        s.splat = c != ' ' && c != '\n' && (c == '!' || c == '?' || spl);
        s.seed = rr.nextLong();
        s.born = born;
        return s;
    }

    // ================================================================ drawing

    @Override
    protected void onDraw(Canvas c) {
        final int w = getWidth(), h = getHeight();
        final long now = System.currentTimeMillis();
        boolean anim = false;

        // --- animation steps
        float dl = caretLine - animLine;
        if (Math.abs(dl) > 0.003f) {
            animLine += dl * 0.22f;
            anim = true;
        } else animLine = caretLine;
        if (scrollReturn && !dragging) {
            scrollBack *= 0.72f;
            if (Math.abs(scrollBack) < 0.5f) {
                scrollBack = 0;
                scrollReturn = false;
            } else anim = true;
        }

        final float topH = 56 * dp;
        final float toolH = 68 * dp;
        final float toolTop = h - toolH;
        final float barY = topH + (toolTop - topH) * 0.6f;
        final float paperL = (w - paperW) / 2f;
        final float textL = paperL + marginX;
        final float base0 = barY - 16 * dp - animLine * lineH + scrollBack;
        lastBase0 = base0;
        lastTextL = textL;

        if (now - kbCheckedAt > 600) {
            kbCheckedAt = now;
            boolean k = detectKbRtl();
            if (k != kbRtl) {
                kbRtl = k;
                relayout();
            }
        }
        float targetX = textL + caretX + (caretRtl ? -charW / 2f : charW / 2f);
        if (headX < 0) headX = targetX;
        float dx = targetX - headX;
        if (Math.abs(dx) > 0.3f) {
            headX += dx * (Math.abs(dx) > charW * 3 ? 0.2f : 0.45f);
            anim = true;
        } else headX = targetX;

        // --- background
        c.drawColor(TOP_BG);

        // --- paper
        float paperTop = base0 - fontPx - 36 * dp;
        float paperBottom = h + 40 * dp;
        r.set(paperL, paperTop, paperL + paperW, paperBottom);
        fill.setShader(null);
        fill.setColor(TONES[tone]);
        if (Build.VERSION.SDK_INT >= 28) fill.setShadowLayer(18 * dp, 0, 6 * dp, 0x66000000);
        c.drawRoundRect(r, 26 * dp, 26 * dp, fill);
        fill.clearShadowLayer();
        grainMatrix.setTranslate(paperL, paperTop);
        grainPaint.getShader().setLocalMatrix(grainMatrix);
        c.drawRoundRect(r, 26 * dp, 26 * dp, grainPaint);

        // paper curving round the platen: shade just above and below the bar
        c.save();
        c.clipRect(paperL, Math.max(paperTop, 0), paperL + paperW, h);
        fill.setShader(new LinearGradient(0, barY - 40 * dp, 0, barY, 0x00000000, 0x2A000000, Shader.TileMode.CLAMP));
        c.drawRect(paperL, barY - 40 * dp, paperL + paperW, barY, fill);
        fill.setShader(new LinearGradient(0, barY, 0, barY + 34 * dp, 0x40000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(paperL, barY, paperL + paperW, barY + 34 * dp, fill);
        fill.setShader(null);
        c.restore();

        // --- the typed letters
        if (text.length() == 0) {
            ink.setAlpha(80);
            if (kbRtl) {
                ink.setTextAlign(Paint.Align.RIGHT);
                c.drawText("ابدأ هنا.", textL + textW, base0, ink);
                ink.setTextAlign(Paint.Align.LEFT);
            } else {
                c.drawText("Begin here.", textL, base0, ink);
            }
        } else {
            anim |= drawLetters(c, textL, base0, topH - lineH, h + lineH, now, true);
        }

        // --- cursor: a blinking underline under the cell where the next letter lands
        float baseCur = base0 + caretLine * lineH;
        boolean blinkOn = ((now - caretMovedAt) / 530) % 2 == 0;
        if (blinkOn) {
            fill.setShader(null);
            fill.setColor(0xFFB0475F);
            float cx0 = textL + (caretRtl ? caretX - charW : caretX);
            c.drawRect(cx0 + 1 * dp, baseCur + 3 * dp, cx0 + charW - 1 * dp, baseCur + 4.6f * dp, fill);
        }

        // --- type bar (the little "T" that strikes)
        long st = now - strikeAt;
        float e = st < 60 ? st / 60f : st < 180 ? 1f - (st - 60) / 120f : 0f;
        if (st < 200) anim = true;
        // The type bar belongs to the machine, not the paper: it stays at the typing line
        // above the bar even while the page is dragged to read back.
        float strikeLine = baseCur - scrollBack;
        float restTip = strikeLine + 5 * dp;
        float hitTip = strikeLine - fontPx * 0.72f;
        float tip = restTip + (hitTip - restTip) * e;
        stroke.setShader(null);
        stroke.setColor(lamp ? 0xFF3B3337 : 0xFF2A2427);
        stroke.setStrokeWidth(2.2f * dp);
        c.drawLine(headX, barY - 9 * dp, headX, tip, stroke);
        stroke.setStrokeWidth(2.6f * dp);
        c.drawLine(headX - 5 * dp, tip, headX + 5 * dp, tip, stroke);

        // --- platen bar
        float bt = 4.5f * dp;
        fill.setShader(new LinearGradient(0, barY - bt, 0, barY + bt,
                new int[]{0xFF4A464E, 0xFF1A181C, 0xFF0B0A0C, 0xFF2C2A30},
                new float[]{0f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        r.set(4 * dp, barY - bt, w - 4 * dp, barY + bt);
        c.drawRoundRect(r, bt, bt, fill);
        fill.setShader(null);
        fill.setColor(0xFF2E2B31);
        c.drawCircle(8 * dp, barY, 7 * dp, fill);
        c.drawCircle(w - 8 * dp, barY, 7 * dp, fill);

        // --- carriage pill (drag it sideways to move the cursor along the line)
        pillHit.set(headX - 38 * dp, barY - 24 * dp, headX + 38 * dp, barY + 24 * dp);
        r.set(headX - 25 * dp, barY - 10 * dp, headX + 25 * dp, barY + 10 * dp);
        fill.setColor(lamp ? 0xFF2B2629 : 0xFFF6ECEF);
        if (Build.VERSION.SDK_INT >= 28) fill.setShadowLayer(4 * dp, 0, 1.5f * dp, 0x55000000);
        c.drawRoundRect(r, 10 * dp, 10 * dp, fill);
        fill.clearShadowLayer();
        int[] dots = lamp ? new int[]{0xFFFFB347, 0xFFFF6B4A, 0xFFFFB347} : new int[]{0xFF2A2427, 0xFF2A2427, 0xFF2A2427};
        for (int i = 0; i < 3; i++) {
            fill.setColor(dots[i]);
            c.drawCircle(headX - 12 * dp + i * 6 * dp, barY, 1.9f * dp, fill);
        }

        // --- lamp mode: darken everything except a cone of warm light from the carriage
        if (lamp) drawLamp(c, w, h, barY, paperW);

        // --- top bar, faded into the page
        fill.setShader(null);
        fill.setColor(TOP_BG);
        c.drawRect(0, 0, w, topH, fill);
        fill.setShader(new LinearGradient(0, topH, 0, topH + 30 * dp, TOP_BG, TOP_BG & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, topH, w, topH + 30 * dp, fill);
        fill.setShader(null);
        drawTopBar(c, w, topH);

        // --- tool pill
        drawTools(c, w, toolTop);

        // --- toast
        long ta = now - toastAt;
        if (ta < 1400 && toast.length() > 0) {
            float a = ta < 150 ? ta / 150f : ta > 1100 ? (1400 - ta) / 300f : 1f;
            label.setTextSize(11.5f * sp);
            label.setTextAlign(Paint.Align.CENTER);
            float tw = label.measureText(toast) + 28 * dp;
            r.set(w / 2f - tw / 2f, toolTop - 34 * dp, w / 2f + tw / 2f, toolTop - 8 * dp);
            fill.setColor(0xE61C1A1E);
            fill.setAlpha((int) (230 * a));
            c.drawRoundRect(r, 13 * dp, 13 * dp, fill);
            label.setColor(0xFFF1E6EA);
            label.setAlpha((int) (255 * a));
            c.drawText(toast, w / 2f, r.centerY() + label.getTextSize() * 0.35f, label);
            anim = true;
        }

        if (anim) postInvalidateOnAnimation();
        else postInvalidateDelayed(530 - (now - caretMovedAt) % 530);
    }

    /** Draws the letters; returns true while any of them is still being stamped. */
    private boolean drawLetters(Canvas c, float left, float base0, float minY, float maxY, long now, boolean live) {
        boolean animating = false;
        int n = text.length();
        if (Build.VERSION.SDK_INT >= 28 || !live) ink.setShadowLayer(0.7f * dp, 0, 0, 0x55231E21);
        ink.setColor(INK);
        // left-to-right letters, each stamped on its own
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == ' ' || Character.isLowSurrogate(ch) || rtlOf[i]) continue;
            float y = base0 + lineOf[i] * lineH;
            if (y < minY || y > maxY) continue;
            int end = (Character.isHighSurrogate(ch) && i + 1 < n) ? i + 2 : i + 1;
            Stamp s = i < stamps.size() ? stamps.get(i) : null;
            float cw = wOf[i] > 0 ? wOf[i] : charW;
            float x = left + xOf[i];
            float k = 1f;
            if (live && s != null && s.born > 0) {
                long age = now - s.born;
                if (age < 170) {
                    k = age / 170f;
                    animating = true;
                }
            }
            float scale = 1f + 0.35f * (1f - k) * (1f - k);
            float alpha = s == null ? 1f : s.alpha;
            int a = (int) (255 * alpha * (0.3f + 0.7f * k));
            c.save();
            c.translate(x + cw / 2f + (s == null ? 0 : s.dx), y + (s == null ? 0 : s.dy));
            if (s != null) c.rotate(s.rot);
            if (scale != 1f) c.scale(scale, scale);
            ink.setAlpha(a);
            c.drawText(text, i, end, -cw / 2f, 0, ink);
            if (s != null && s.dbl) {
                ink.setAlpha(a / 3);
                c.drawText(text, i, end, -cw / 2f + 0.7f * dp, 0.25f * dp, ink);
            }
            c.restore();
            if (s != null && s.splat && k >= 1f) drawSplat(c, x, y, s.seed);
        }
        // right-to-left (Arabic) runs, drawn whole so the letters join
        for (Run run : runs) {
            float y = base0 + run.line * lineH;
            if (y < minY || y > maxY) continue;
            long newest = 0;
            for (int j = run.s; j < run.e && j < stamps.size(); j++) newest = Math.max(newest, stamps.get(j).born);
            float k = 1f;
            if (live && newest > 0 && now - newest < 170) {
                k = (now - newest) / 170f;
                animating = true;
            }
            Stamp s0 = run.s < stamps.size() ? stamps.get(run.s) : null;
            float alpha = s0 == null ? 1f : 0.85f + 0.15f * s0.alpha;
            ink.setAlpha((int) (255 * alpha * (0.6f + 0.4f * k)));
            c.drawTextRun(text, run.s, run.e, run.s, run.e, left + run.x, y + (s0 == null ? 0 : s0.dy * 0.5f), true, ink);
        }
        ink.clearShadowLayer();
        ink.setAlpha(255);
        return animating;
    }

    private void drawSplat(Canvas c, float x, float y, long seed) {
        Random rr = new Random(seed);
        int count = 3 + rr.nextInt(4);
        fill.setShader(null);
        for (int j = 0; j < count; j++) {
            float px = x + charW * (0.5f + (rr.nextFloat() - 0.3f) * 1.8f);
            float py = y - fontPx * (0.2f + rr.nextFloat() * 0.8f);
            float rad = (0.35f + rr.nextFloat() * 1.0f) * dp;
            fill.setColor(INK);
            fill.setAlpha(50 + rr.nextInt(80));
            c.drawCircle(px, py, rad, fill);
        }
    }

    private void drawLamp(Canvas c, int w, int h, float barY, float pw) {
        drawLamp(c, w, h, barY, pw, Math.max(barY * 0.95f, 220 * dp));
    }

    private void drawLamp(Canvas c, int w, int h, float barY, float pw, float reach) {
        float half = Math.max(pw * 0.95f, w * 0.8f);   // a wide beam (about 100 degrees)
        path.reset();
        path.moveTo(headX - 14 * dp, barY);
        path.lineTo(headX - half, barY - reach);
        path.lineTo(headX + half, barY - reach);
        path.lineTo(headX + 14 * dp, barY);
        path.close();

        int layer = c.saveLayer(0, 0, w, h, null);
        c.drawColor(0xEB0C0B0E);
        cut.setShader(new RadialGradient(headX, barY, reach,
                new int[]{0xFF000000, 0xDD000000, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, cut);
        cut.setShader(new RadialGradient(headX, barY, 34 * dp, 0xAA000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(headX, barY, 34 * dp, cut);
        c.restoreToCount(layer);

        // warm tint inside the cone
        fill.setShader(new RadialGradient(headX, barY, reach,
                new int[]{0x55FFB070, 0x22FF9A5C, 0x00FF9A5C}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
        fill.setShader(null);
    }

    private void drawTopBar(Canvas c, int w, float topH) {
        float cy = topH / 2f;
        int fg = 0xFFCFC6CA;
        stroke.setShader(null);
        stroke.setColor(fg);
        stroke.setStrokeWidth(1.9f * dp);

        // back arrow
        float bx = 26 * dp;
        c.drawLine(bx - 8 * dp, cy, bx + 8 * dp, cy, stroke);
        c.drawLine(bx - 8 * dp, cy, bx - 2 * dp, cy - 6 * dp, stroke);
        c.drawLine(bx - 8 * dp, cy, bx - 2 * dp, cy + 6 * dp, stroke);
        backHit.set(0, 0, 60 * dp, topH);

        // share (box with an arrow out of it)
        float sx = w - 26 * dp;
        path.reset();
        path.moveTo(sx - 4 * dp, cy - 3 * dp);
        path.lineTo(sx - 7 * dp, cy - 3 * dp);
        path.lineTo(sx - 7 * dp, cy + 8 * dp);
        path.lineTo(sx + 7 * dp, cy + 8 * dp);
        path.lineTo(sx + 7 * dp, cy - 3 * dp);
        path.lineTo(sx + 4 * dp, cy - 3 * dp);
        c.drawPath(path, stroke);
        c.drawLine(sx, cy - 9 * dp, sx, cy + 3 * dp, stroke);
        c.drawLine(sx, cy - 9 * dp, sx - 4 * dp, cy - 5 * dp, stroke);
        c.drawLine(sx, cy - 9 * dp, sx + 4 * dp, cy - 5 * dp, stroke);
        shareHit.set(w - 60 * dp, 0, w, topH);

        // character count
        label.setTextSize(11 * sp);
        label.setTextAlign(Paint.Align.RIGHT);
        label.setColor(text.length() >= LIMIT ? 0xFFFF8A7A : 0xFFA59DA1);
        c.drawText(nf.format(text.length()) + " / " + nf.format(LIMIT), w - 52 * dp, cy + 4 * dp, label);
    }

    private void drawTools(Canvas c, int w, float toolTop) {
        float pillW = Math.min(w - 20 * dp, 400 * dp);
        float l = (w - pillW) / 2f;
        r.set(l, toolTop + 12 * dp, l + pillW, toolTop + 56 * dp);
        fill.setShader(null);
        fill.setColor(lamp ? 0xFF241F22 : 0xFFE9DCE0);
        c.drawRoundRect(r, 22 * dp, 22 * dp, fill);
        float cy = r.centerY();
        float step = pillW / 8f;
        int on = lamp ? 0xFFFFC27A : 0xFF2A2427;
        int off = lamp ? 0xFF7B7276 : 0xFF9C8E93;
        stroke.setShader(null);
        stroke.setStrokeWidth(1.8f * dp);
        for (int slot = 0; slot < 8; slot++) {
            float cx = l + step * slot + step / 2f;
            toolHits[slot].set(cx - step / 2f, r.top - 6 * dp, cx + step / 2f, r.bottom + 6 * dp);
            if (slot == 0 || slot == 7) { // cursor arrows
                float d = slot == 0 ? -1 : 1;
                stroke.setColor(arrowHeld == (slot == 0 ? -1 : 1) ? (lamp ? 0xFFFFE2B8 : 0xFF000000) : on);
                stroke.setStrokeWidth(2.2f * dp);
                c.drawLine(cx + 3 * dp * d, cy - 7 * dp, cx - 3 * dp * d, cy, stroke);
                c.drawLine(cx - 3 * dp * d, cy, cx + 3 * dp * d, cy + 7 * dp, stroke);
                stroke.setStrokeWidth(1.8f * dp);
                continue;
            }
            int i = slot - 1;
            switch (i) {
                case 0: // lamp
                    stroke.setColor(lamp ? on : off);
                    c.drawCircle(cx, cy - 3 * dp, 6 * dp, stroke);
                    c.drawLine(cx - 3 * dp, cy + 5 * dp, cx + 3 * dp, cy + 5 * dp, stroke);
                    c.drawLine(cx - 2 * dp, cy + 8 * dp, cx + 2 * dp, cy + 8 * dp, stroke);
                    if (lamp) {
                        fill.setColor(on);
                        c.drawCircle(cx, cy - 3 * dp, 3 * dp, fill);
                    }
                    break;
                case 1: // sound
                    stroke.setColor(sound ? on : off);
                    path.reset();
                    path.moveTo(cx - 8 * dp, cy - 3 * dp);
                    path.lineTo(cx - 4 * dp, cy - 3 * dp);
                    path.lineTo(cx + 1 * dp, cy - 8 * dp);
                    path.lineTo(cx + 1 * dp, cy + 8 * dp);
                    path.lineTo(cx - 4 * dp, cy + 3 * dp);
                    path.lineTo(cx - 8 * dp, cy + 3 * dp);
                    path.close();
                    c.drawPath(path, stroke);
                    if (sound) {
                        r.set(cx - 2 * dp, cy - 6 * dp, cx + 8 * dp, cy + 6 * dp);
                        c.drawArc(r, -50, 100, false, stroke);
                        r.set(l, toolTop + 12 * dp, l + pillW, toolTop + 56 * dp);
                    } else {
                        c.drawLine(cx + 4 * dp, cy - 3 * dp, cx + 9 * dp, cy + 3 * dp, stroke);
                        c.drawLine(cx + 9 * dp, cy - 3 * dp, cx + 4 * dp, cy + 3 * dp, stroke);
                    }
                    break;
                case 2: // paper tone
                    fill.setColor(TONES[tone]);
                    RectF pr = new RectF(cx - 6 * dp, cy - 8 * dp, cx + 6 * dp, cy + 8 * dp);
                    c.drawRoundRect(pr, 2 * dp, 2 * dp, fill);
                    stroke.setColor(on);
                    c.drawRoundRect(pr, 2 * dp, 2 * dp, stroke);
                    c.drawLine(cx - 3 * dp, cy - 3 * dp, cx + 3 * dp, cy - 3 * dp, stroke);
                    c.drawLine(cx - 3 * dp, cy + 1 * dp, cx + 2 * dp, cy + 1 * dp, stroke);
                    break;
                case 3: // type size
                    label.setTextAlign(Paint.Align.CENTER);
                    label.setColor(on);
                    label.setTextSize((11 + sizeIdx * 3) * sp);
                    c.drawText("Aa", cx, cy + label.getTextSize() * 0.33f, label);
                    break;
                case 4: // Arabic font, shown in itself
                    label.setTextAlign(Paint.Align.CENTER);
                    label.setColor(on);
                    label.setTextSize(19 * sp);
                    c.drawText("ع", cx, cy + 6 * dp, label);
                    break;
                default: // keyboard
                    stroke.setColor(on);
                    RectF kr = new RectF(cx - 9 * dp, cy - 6 * dp, cx + 9 * dp, cy + 6 * dp);
                    c.drawRoundRect(kr, 2.5f * dp, 2.5f * dp, stroke);
                    fill.setColor(on);
                    for (int k = 0; k < 4; k++) c.drawCircle(cx - 5 * dp + k * 3.3f * dp, cy - 2 * dp, 0.9f * dp, fill);
                    c.drawLine(cx - 4 * dp, cy + 2.5f * dp, cx + 4 * dp, cy + 2.5f * dp, stroke);
                    break;
            }
        }
    }

    private void showToast(String s) {
        toast = s;
        toastAt = System.currentTimeMillis();
        invalidate();
    }

    // ================================================================ touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragStart = scrollBack;
                dragging = false;
                scrollReturn = false;
                arrowRepeated = false;
                longPressed = false;
                pillDrag = text.length() > 0 && pillHit.contains(downX, downY);
                if (pillDrag) {
                    pillStartLine = caretLine;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                }
                if (onPaper(downX, downY)) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                if (toolHits[0].contains(downX, downY) || toolHits[7].contains(downX, downY)) {
                    arrowHeld = toolHits[0].contains(downX, downY) ? -1 : 1;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    moveCaret(arrowHeld);
                    postDelayed(repeater, 380);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY;
                if (pillDrag) {
                    // sideways moves along the line; up and down move to earlier or later lines
                    int line = pillStartLine + Math.round(dy / (lineH * 0.8f));
                    line = Math.max(0, Math.min(cursorLine, line));
                    int p = indexAtX(line, e.getX() - lastTextL);
                    if (p != caret()) {
                        Selection.setSelection(text, p);
                        onCaretMoved();
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    return true;
                }
                if (arrowHeld != 0 || longPressed) return true;
                if (!dragging && (Math.abs(dy) > touchSlop || Math.abs(e.getX() - downX) > touchSlop)) {
                    dragging = true;
                    removeCallbacks(longPress);
                }
                if (dragging) {
                    float max = caretLine * lineH;
                    float min = -(cursorLine - caretLine) * lineH;
                    scrollBack = Math.max(min, Math.min(max, dragStart + dy));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (pillDrag) {
                    pillDrag = false;
                } else if (longPressed) {
                    longPressed = false;
                } else if (arrowHeld != 0) {
                    arrowHeld = 0;
                    removeCallbacks(repeater);
                    invalidate();
                } else if (!dragging) tap(e.getX(), e.getY());
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                pillDrag = false;
                removeCallbacks(longPress);
                longPressed = false;
                arrowHeld = 0;
                removeCallbacks(repeater);
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void tap(float x, float y) {
        if (backHit.contains(x, y)) {
            host.onBack();
            return;
        }
        if (shareHit.contains(x, y)) {
            host.onShare();
            return;
        }
        for (int i = 1; i <= 6; i++) {
            if (toolHits[i].contains(x, y)) {
                tool(i - 1);
                return;
            }
        }
        // a tap on the typed text puts the cursor there
        if (onPaper(x, y)) placeCaretAt(x, y);
        showKeyboard();
    }

    private boolean onPaper(float x, float y) {
        return y > backHit.bottom && y < toolHits[1].top - 6 * dp;
    }

    private void placeCaretAt(float x, float y) {
        if (text.length() == 0) return;
        int line = (int) Math.floor((y - (lastBase0 - fontPx * 1.1f)) / lineH);
        line = Math.max(0, Math.min(cursorLine, line));
        Selection.setSelection(text, indexAtX(line, x - lastTextL));
        onCaretMoved();
    }

    // ================================================================ copy and paste

    private boolean longPressed;
    private final RectF pillHit = new RectF();
    private boolean pillDrag;
    private int pillStartLine;
    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            longPressed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            placeCaretAt(downX, downY);
            showClipMenu(downX, downY);
        }
    };

    /** The usual floating Paste / Copy menu, shown where the page was held. */
    private void showClipMenu(float x, float y) {
        final Rect anchor = new Rect((int) x - 2, (int) y - 2, (int) x + 2, (int) y + 2);
        startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                if (clipText() != null) menu.add(0, 1, 0, "Paste");
                if (text.length() > 0) menu.add(0, 2, 1, "Copy all");
                return menu.size() > 0;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == 1) paste();
                else copyAll();
                mode.finish();
                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                outRect.set(anchor);
            }
        }, ActionMode.TYPE_FLOATING);
    }

    private String clipText() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return null;
        CharSequence t = cd.getItemAt(0).coerceToText(getContext());
        if (t == null || t.length() == 0) return null;
        return t.toString().replace("\r\n", "\n").replace('\r', '\n');
    }

    void paste() {
        String s = clipText();
        if (s == null) {
            showToast("Nothing to paste");
            return;
        }
        int a = Math.min(selStart(), caret()), b = Math.max(selStart(), caret());
        int room = LIMIT - (text.length() - (b - a));
        if (room <= 0) {
            showToast("Page full · 3,000 characters");
            return;
        }
        if (s.length() > room) {
            int cut = room;
            if (cut > 0 && Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
            s = s.substring(0, cut);
            showToast("Pasted what fits on the page");
        }
        typeAtCaret(s);
    }

    void copyAll() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Platen page", text.toString()));
        showToast("Page copied");
    }

    private void tool(int i) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        SharedPreferences.Editor ed = prefs.edit();
        switch (i) {
            case 0:
                lamp = !lamp;
                ed.putBoolean("lamp", lamp);
                showToast(lamp ? "Lamp on" : "Lamp off");
                break;
            case 1:
                sound = !sound;
                ed.putBoolean("sound", sound);
                showToast(sound ? "Sound on" : "Sound off");
                break;
            case 2:
                tone = (tone + 1) % TONES.length;
                ed.putInt("tone", tone);
                showToast(TONE_NAMES[tone]);
                break;
            case 3:
                sizeIdx = (sizeIdx + 1) % SIZES.length;
                ed.putInt("size", sizeIdx);
                applySize();
                animLine = caretLine;
                headX = -1;
                showToast(SIZE_NAMES[sizeIdx]);
                break;
            case 4: // Arabic font, used on every page
                arFont = (arFont + 1) % faces.length;
                ed.putInt("arFont", arFont);
                face = faces[arFont];
                ink.setTypeface(face);
                label.setTypeface(face);
                applySize();
                animLine = caretLine;
                headX = -1;
                showToast(ARABIC_FONT_NAMES[Math.min(arFont, ARABIC_FONT_NAMES.length - 1)]);
                break;
            default:
                if (isKeyboardVisible()) hideKeyboard();
                else showKeyboard();
                break;
        }
        ed.apply();
        invalidate();
    }

    private boolean isKeyboardVisible() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets in = getRootWindowInsets();
            if (in != null) return in.isVisible(WindowInsets.Type.ime());
        }
        return kbShown;
    }

    // ================================================================ paper grain

    private static Bitmap makeGrain() {
        int s = 192;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Random rr = new Random(42);
        int[] px = new int[s * s];
        for (int i = 0; i < px.length; i++) {
            float v = rr.nextFloat();
            int a;
            if (v < 0.006f) a = 40 + rr.nextInt(40);      // a fleck in the paper
            else a = rr.nextInt(12);                      // fine tooth
            int shade = rr.nextFloat() < 0.5f ? 0 : 255;
            px[i] = Color.argb(shade == 0 ? a : a / 2, shade, shade, shade);
        }
        b.setPixels(px, 0, s, 0, 0, s, s);
        return b;
    }
}
