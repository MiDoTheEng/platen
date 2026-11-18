package com.platen.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The list of pages: each one a small slip of paper. Tap to open, hold to delete. */
@SuppressLint("ViewConstructor")
public class HomeView extends FrameLayout {

    public interface Host {
        void open(Store.Note n);

        void create();

        void delete(Store.Note n);
    }

    private static final int INK = 0xFF231E21;

    public HomeView(Context c, List<Store.Note> notes, int paper, Typeface face, Host host) {
        super(c);
        float dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF0F0E11);

        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        col.setPadding(pad, (int) (28 * dp), pad, (int) (120 * dp));
        scroll.addView(col, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        TextView title = new TextView(c);
        title.setText("PLATEN");
        title.setTypeface(face);
        title.setTextSize(30);
        title.setLetterSpacing(0.25f);
        title.setTextColor(paper);
        col.addView(title);

        TextView sub = new TextView(c);
        sub.setTypeface(face);
        sub.setTextSize(13);
        sub.setTextColor(0xFF8F878B);
        sub.setText(notes.isEmpty() ? "A blank page is waiting"
                : notes.size() == 1 ? "1 page" : notes.size() + " pages");
        sub.setPadding(0, (int) (4 * dp), 0, (int) (18 * dp));
        col.addView(sub);

        SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault());
        for (Store.Note n : notes) {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(paper);
            bg.setCornerRadius(14 * dp);
            card.setBackground(bg);
            card.setElevation(3 * dp);
            int cp = (int) (16 * dp);
            card.setPadding(cp, cp, cp, (int) (12 * dp));

            TextView body = new TextView(c);
            body.setTypeface(face);
            body.setTextSize(14);
            body.setTextColor(INK);
            body.setMaxLines(4);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setLineSpacing(0, 1.2f);
            body.setText(n.text.trim());
            card.addView(body);

            TextView meta = new TextView(c);
            meta.setTypeface(face);
            meta.setTextSize(10.5f);
            meta.setTextColor(0x99231E21);
            meta.setPadding(0, (int) (10 * dp), 0, 0);
            meta.setText(fmt.format(new Date(n.updated)) + "  ·  " + n.text.length() + " / 3,000");
            card.addView(meta);

            card.setOnClickListener(v -> host.open(n));
            card.setOnLongClickListener(v -> {
                host.delete(n);
                return true;
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (12 * dp);
            col.addView(card, lp);
        }

        if (notes.isEmpty()) {
            TextView hint = new TextView(c);
            hint.setTypeface(face);
            hint.setTextSize(13);
            hint.setTextColor(0xFF6E676B);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(0, 1.3f);
            hint.setText("Feed in a sheet and start typing.\nEvery page saves itself.");
            hint.setPadding(0, (int) (120 * dp), 0, 0);
            col.addView(hint);
        }

        TextView add = new TextView(c);
        add.setTypeface(face);
        add.setTextSize(15);
        add.setTextColor(INK);
        add.setText("+  New page");
        add.setGravity(Gravity.CENTER);
        GradientDrawable abg = new GradientDrawable();
        abg.setColor(paper);
        abg.setCornerRadius(26 * dp);
        add.setBackground(abg);
        add.setElevation(8 * dp);
        add.setOnClickListener(v -> host.create());
        LayoutParams alp = new LayoutParams((int) (190 * dp), (int) (52 * dp), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        alp.bottomMargin = (int) (28 * dp);
        addView(add, alp);
        add.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
}
