package com.platen.app;

import android.annotation.SuppressLint;
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
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

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

    private static final int INK = 0xFF231E21;
    private static final int TOP_BG = 0xFF0F0E11;

    private final Host host;
    private final Sounds sounds;
    private final SharedPreferences prefs;
    private final float dp, sp;
    private final Typeface face;
    private final InputMethodManager imm;
    private final int touchSlop;

    // --- text and per-letter ink stamps
    private SpannableStringBuilder text = new SpannableStringBuilder();
    private final ArrayList<Stamp> stamps = new ArrayList<>();
    private final Watcher watcher = new Watcher();

    // --- layout (monospaced grid)
    private int[] lineOf = new int[0], colOf = new int[0];
    private int cursorLine, cursorCol, cols = 30;   // end of the text
    private int caretLine, caretCol;                // where the next letter goes
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
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix grainMatrix = new Matrix();
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final RectF backHit = new RectF(), shareHit = new RectF();
    private final RectF[] toolHits = {new RectF(), new RectF(), new RectF(), new RectF(),
            new RectF(), new RectF(), new RectF()};
    private final NumberFormat nf = NumberFormat.getIntegerInstance();

    public TypewriterView(Context c, Sounds sounds, SharedPreferences prefs, Typeface face, Host host) {
        super(c);
        this.host = host;
        this.sounds = sounds;
        this.prefs = prefs;
        this.face = face;
        dp = getResources().getDisplayMetrics().density;
        sp = dp * getResources().getConfiguration().fontScale;
        imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();

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
        drawLetters(c, marginX, top + fontPx * 0.8f, -1e9f, 1e9f, 0, false);
        return b;
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
        if (dir == -1) {
            if (p > 0) p -= Character.charCount(Character.codePointBefore(text, p));
        } else if (dir == 1) {
            if (p < n) p += Character.charCount(Character.codePointAt(text, p));
        } else {
            int target = caretLine + (dir > 0 ? 1 : -1);
            if (target < 0) p = 0;
            else if (target > cursorLine) p = n;
            else p = indexAt(target, caretCol);
        }
        Selection.setSelection(text, p);
        onCaretMoved();
    }

    /** The cursor position on a line nearest to (at or before) a column. */
    private int indexAt(int line, int col) {
        int n = text.length();
        int best = -1, bestCol = -1;
        for (int p = 0; p <= n; p++) {
            if (p < n && Character.isLowSurrogate(text.charAt(p))) continue;
            int L = p < n ? lineOf[p] : cursorLine;
            int C = p < n ? colOf[p] : cursorCol;
            if (L == line && C <= col && C > bestCol) {
                best = p;
                bestCol = C;
            }
            if (L > line) break;
        }
        return best < 0 ? caret() : best;
    }

    private void updateCaret() {
        int p = caret();
        if (p < text.length() && p < lineOf.length) {
            caretLine = lineOf[p];
            caretCol = colOf[p];
        } else {
            caretLine = cursorLine;
            caretCol = cursorCol;
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
                Selection.setSelection(text, indexAt(caretLine, 0));
                onCaretMoved();
                return true;
            case KeyEvent.KEYCODE_MOVE_END:
                Selection.setSelection(text, indexAt(caretLine, Integer.MAX_VALUE));
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
            if (caretCol >= cols - 6 && caretCol < cols && bellLine != caretLine && last != '\n') {
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

    /** Word-wrapped monospaced layout: the line and column of every character. */
    private void relayout() {
        int n = text.length();
        if (lineOf.length < n + 1) {
            lineOf = new int[n + 64];
            colOf = new int[n + 64];
        }
        int cur = 0, col = 0, lineStart = 0, lastSpace = -1;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lineOf[i] = cur;
                colOf[i] = col;
                cur++;
                col = 0;
                lineStart = i + 1;
                lastSpace = -1;
                continue;
            }
            if (Character.isLowSurrogate(c) && i > 0) { // second half of an emoji: same cell
                lineOf[i] = lineOf[i - 1];
                colOf[i] = colOf[i - 1];
                continue;
            }
            if (col >= cols) {
                if (c == ' ') {
                    lineOf[i] = cur;
                    colOf[i] = col;
                    cur++;
                    col = 0;
                    lineStart = i + 1;
                    lastSpace = -1;
                    continue;
                } else if (lastSpace >= lineStart) {
                    cur++;
                    int c0 = 0;
                    for (int j = lastSpace + 1; j < i; j++) {
                        lineOf[j] = cur;
                        colOf[j] = Character.isLowSurrogate(text.charAt(j)) ? Math.max(0, c0 - 1) : c0++;
                    }
                    col = c0;
                    lineStart = lastSpace + 1;
                    lastSpace = -1;
                } else {
                    cur++;
                    col = 0;
                    lineStart = i;
                }
            }
            lineOf[i] = cur;
            colOf[i] = col;
            if (c == ' ') lastSpace = i;
            col++;
        }
        cursorLine = cur;
        cursorCol = col;
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

        int col = Math.min(caretCol, cols);
        float targetX = textL + col * charW + charW / 2f;
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
            c.drawText("Begin here.", textL, base0, ink);
        } else {
            anim |= drawLetters(c, textL, base0, topH - lineH, h + lineH, now, true);
        }

        // --- cursor: a blinking underline under the cell where the next letter lands
        float baseCur = base0 + caretLine * lineH;
        boolean blinkOn = ((now - caretMovedAt) / 530) % 2 == 0;
        if (blinkOn) {
            fill.setShader(null);
            fill.setColor(0xFFB0475F);
            float cx0 = textL + Math.min(caretCol, cols) * charW;
            c.drawRect(cx0 + 1 * dp, baseCur + 3 * dp, cx0 + charW - 1 * dp, baseCur + 4.6f * dp, fill);
        }

        // --- type bar (the little "T" that strikes)
        long st = now - strikeAt;
        float e = st < 60 ? st / 60f : st < 180 ? 1f - (st - 60) / 120f : 0f;
        if (st < 200) anim = true;
        float restTip = baseCur + 5 * dp;
        float hitTip = baseCur - fontPx * 0.72f;
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

        // --- carriage pill
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
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == ' ' || Character.isLowSurrogate(ch)) continue;
            float y = base0 + lineOf[i] * lineH;
            if (y < minY || y > maxY) continue;
            int end = (Character.isHighSurrogate(ch) && i + 1 < n) ? i + 2 : i + 1;
            Stamp s = i < stamps.size() ? stamps.get(i) : null;
            float x = left + colOf[i] * charW;
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
            c.translate(x + charW / 2f + (s == null ? 0 : s.dx), y + (s == null ? 0 : s.dy));
            if (s != null) c.rotate(s.rot);
            if (scale != 1f) c.scale(scale, scale);
            ink.setColor(INK);
            ink.setAlpha(a);
            c.drawText(text, i, end, -charW / 2f, 0, ink);
            if (s != null && s.dbl) {
                ink.setAlpha(a / 3);
                c.drawText(text, i, end, -charW / 2f + 0.7f * dp, 0.25f * dp, ink);
            }
            c.restore();
            if (s != null && s.splat && k >= 1f) drawSplat(c, x, y, s.seed);
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
        float reach = Math.max(barY * 0.95f, 220 * dp);
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
        float pillW = Math.min(w - 24 * dp, 360 * dp);
        float l = (w - pillW) / 2f;
        r.set(l, toolTop + 12 * dp, l + pillW, toolTop + 56 * dp);
        fill.setShader(null);
        fill.setColor(lamp ? 0xFF241F22 : 0xFFE9DCE0);
        c.drawRoundRect(r, 22 * dp, 22 * dp, fill);
        float cy = r.centerY();
        float step = pillW / 7f;
        int on = lamp ? 0xFFFFC27A : 0xFF2A2427;
        int off = lamp ? 0xFF7B7276 : 0xFF9C8E93;
        stroke.setShader(null);
        stroke.setStrokeWidth(1.8f * dp);
        for (int slot = 0; slot < 7; slot++) {
            float cx = l + step * slot + step / 2f;
            toolHits[slot].set(cx - step / 2f, r.top - 6 * dp, cx + step / 2f, r.bottom + 6 * dp);
            if (slot == 0 || slot == 6) { // cursor arrows
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
                if (toolHits[0].contains(downX, downY) || toolHits[6].contains(downX, downY)) {
                    arrowHeld = toolHits[0].contains(downX, downY) ? -1 : 1;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    moveCaret(arrowHeld);
                    postDelayed(repeater, 380);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = e.getY() - downY;
                if (arrowHeld != 0) return true;
                if (!dragging && Math.abs(dy) > touchSlop) dragging = true;
                if (dragging) {
                    float max = caretLine * lineH;
                    float min = -(cursorLine - caretLine) * lineH;
                    scrollBack = Math.max(min, Math.min(max, dragStart + dy));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (arrowHeld != 0) {
                    arrowHeld = 0;
                    removeCallbacks(repeater);
                    invalidate();
                } else if (!dragging) tap(e.getX(), e.getY());
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
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
        for (int i = 1; i <= 5; i++) {
            if (toolHits[i].contains(x, y)) {
                tool(i - 1);
                return;
            }
        }
        // a tap on the typed text puts the cursor there
        if (text.length() > 0 && y > backHit.bottom && y < toolHits[1].top) {
            int line = (int) Math.floor((y - (lastBase0 - fontPx * 1.1f)) / lineH);
            line = Math.max(0, Math.min(cursorLine, line));
            int col = Math.max(0, Math.round((x - lastTextL) / charW));
            Selection.setSelection(text, indexAt(line, col));
            onCaretMoved();
        }
        showKeyboard();
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
