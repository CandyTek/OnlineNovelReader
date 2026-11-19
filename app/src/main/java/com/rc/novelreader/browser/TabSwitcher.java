package com.rc.novelreader.browser;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.util.Urls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The two tab views, both made of the pictures in {@link TabThumbs}.
 *
 * The grid (tab button, or a pull down on the address bar): every tab as a card, two to a row, with its title,
 * a close button and the top of its page.
 *
 * The carousel (a swipe along the address bar or on the tab button), after the recent-apps view of Android: the
 * page shrinks into a card, its neighbours come in from the sides and follow the finger, and on release the
 * row glides to the nearest card, which grows back into the page. Nothing is switched to before the finger
 * lifts: a sleeping tab reloads when it is shown, and the tabs passed on the way were not asked for.
 *
 * Both live in the activity's own window, above the bars: they are views, not dialogs, so the page under them
 * keeps its size and the carousel can start from exactly where the page is.
 */
final class TabSwitcher {
    private final BrowserActivity a;
    /** The window-wide parent, the root inside it that carries the system-bar paddings, and the page area. */
    private final ViewGroup layer;
    private final View fitted, content;
    private View grid;
    private Carousel carousel;
    /** Pictures asked from the files while a view is up (tab ids). */
    private final Set<Long> asked = new HashSet<>();

    TabSwitcher(BrowserActivity a, ViewGroup layer, View fitted, View content) {
        this.a = a;
        this.layer = layer;
        this.fitted = fitted;
        this.content = content;
    }

    boolean gridShown() {
        return grid != null;
    }

    /** A swipe is being followed or is settling. */
    boolean swiping() {
        return carousel != null;
    }

    /** The picture of a tab that has none in memory, from its file; the view is redrawn when it arrives. */
    private void want(final Tab t, final View redraw) {
        if (t.thumb != null || t.incognito || !asked.add(t.id)) return;
        App.IO.execute(() -> {
            final Bitmap b = TabThumbs.load(t.id);
            if (b == null) return;
            App.MAIN.post(() -> {
                if (t.thumb == null) t.thumb = b;
                if (redraw instanceof GridView) ((GridView) redraw).invalidateViews();
                else redraw.invalidate();
            });
        });
    }

    /** Sleeping tabs do not hold their picture in memory while no tab view is up. */
    private void release() {
        asked.clear();
        for (Tab t : a.tabs) {
            if (t.web == null && !t.incognito && t != a.current()) t.thumb = null;
        }
    }

    private static Bitmap iconOf(Tab t) {
        if (t.incognito) return null;
        return t.icon != null ? t.icon : Favicons.get(t.address());
    }

    // ------------------------------------------------------------------ grid

    void showGrid() {
        if (grid != null || carousel != null) return;
        a.snapshot();
        final LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(a.getColor(R.color.page_bg));
        panel.setClickable(true);
        panel.setPadding(fitted.getPaddingLeft(), fitted.getPaddingTop(), fitted.getPaddingRight(), fitted.getPaddingBottom());

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(App.dp(20), 0, App.dp(6), 0);
        final TextView title = new TextView(a);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(a.getColor(R.color.text_primary));
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton done = new ImageButton(a);
        done.setImageResource(R.drawable.ic_close);
        done.setImageTintList(ColorStateList.valueOf(a.getColor(R.color.text_primary)));
        done.setBackgroundResource(a.selectable());
        done.setContentDescription(a.getString(R.string.cancel));
        done.setOnClickListener(v -> dismissGrid());
        head.addView(done, new LinearLayout.LayoutParams(App.dp(48), App.dp(48)));
        panel.addView(head, new LinearLayout.LayoutParams(-1, App.dp(52)));

        final GridView list = new GridView(a);
        list.setNumColumns(2);
        list.setHorizontalSpacing(App.dp(12));
        list.setVerticalSpacing(App.dp(12));
        list.setPadding(App.dp(12), App.dp(4), App.dp(12), App.dp(14));
        list.setClipToPadding(false);
        list.setSelector(new ColorDrawable(0));
        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return a.tabs.size();
            }

            @Override
            public Object getItem(int position) {
                return a.tabs.get(position);
            }

            @Override
            public long getItemId(int position) {
                return a.tabs.get(position).id;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final Tab t = a.tabs.get(position);
                want(t, list);
                final BaseAdapter me = this;
                return card(t, parent, () -> {
                    a.closeTab(t);
                    title.setText(a.getString(R.string.tabs_title, a.tabs.size()));
                    me.notifyDataSetChanged();
                });
            }
        };
        list.setAdapter(adapter);
        title.setText(a.getString(R.string.tabs_title, a.tabs.size()));
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(a);
        actions.setBackgroundColor(a.getColor(R.color.surface));
        actions.setPadding(App.dp(8), App.dp(2), App.dp(8), App.dp(4));
        actions.addView(a.sheetButton(R.drawable.ic_add, R.string.new_tab, () -> {
            dismissGrid();
            a.openTab(null, null, false, false);
        }));
        actions.addView(a.sheetButton(R.drawable.ic_incognito, R.string.incognito_tab, () -> {
            dismissGrid();
            a.openTab(null, null, true, false);
        }));
        actions.addView(a.sheetButton(R.drawable.ic_delete, R.string.close_all_tabs, () -> {
            dismissGrid();
            a.closeAllTabs();
        }));
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        grid = panel;
        layer.addView(panel, 1, new FrameLayout.LayoutParams(-1, -1));
        // the page under the grid is not there for a screen reader (or a test driver) either
        fitted.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        final int at = a.tabs.indexOf(a.current());
        if (at > 1) list.setSelection(at - at % 2);
        panel.setAlpha(0f);
        panel.setScaleX(0.97f);
        panel.setScaleY(0.97f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
    }

    void dismissGrid() {
        if (grid == null) return;
        final View v = grid;
        grid = null;
        fitted.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        v.animate().cancel();
        v.animate().alpha(0f).setDuration(110).withEndAction(() -> layer.removeView(v)).start();
        release();
    }

    /** The row height follows the page: a card shows the top of a page in the shape the page area has. */
    private View card(final Tab t, ViewGroup parent, final Runnable onClose) {
        final boolean current = t == a.current();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(a.getColor(R.color.surface));
        bg.setCornerRadius(App.dp(14));
        box.setBackground(bg);
        box.setClipToOutline(true);
        box.setElevation(App.dp(2));
        // the frame lies over the picture, so it is not cut by it
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0);
        frame.setCornerRadius(App.dp(14));
        frame.setStroke(current ? App.dp(2) : 1, a.getColor(current ? R.color.brand : R.color.divider));
        box.setForeground(frame);

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(App.dp(10), 0, 0, 0);
        ImageView icon = new ImageView(a);
        Bitmap fav = iconOf(t);
        if (fav != null) icon.setImageBitmap(fav);
        else {
            icon.setImageResource(t.incognito ? R.drawable.ic_incognito : t.isHome() ? R.drawable.ic_home : R.drawable.ic_globe);
            icon.setImageTintList(ColorStateList.valueOf(a.getColor(R.color.text_secondary)));
        }
        row.addView(icon, new LinearLayout.LayoutParams(App.dp(18), App.dp(18)));
        TextView name = new TextView(a);
        name.setText(t.label());
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(a.getColor(current ? R.color.brand : R.color.text_primary));
        if (current) name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setPadding(App.dp(8), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = new ImageButton(a);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(a.getColor(R.color.text_secondary)));
        close.setBackgroundResource(a.selectable());
        close.setPadding(App.dp(9), App.dp(9), App.dp(9), App.dp(9));
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        close.setFocusable(false);
        close.setContentDescription(a.getString(R.string.act_close_tab));
        close.setOnClickListener(v -> onClose.run());
        row.addView(close, new LinearLayout.LayoutParams(App.dp(38), App.dp(38)));
        box.addView(row, new LinearLayout.LayoutParams(-1, App.dp(38)));

        int colW = Math.max(App.dp(80), (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight() - App.dp(12)) / 2);
        float shape = content.getWidth() > 0 ? (float) content.getHeight() / content.getWidth() : 1.3f;
        int h = Math.round(colW * Math.max(0.5f, Math.min(1.25f, shape)));
        box.addView(new Thumb(t), new LinearLayout.LayoutParams(-1, h));
        box.setOnClickListener(v -> {
            dismissGrid();
            if (t != a.current() && a.tabs.contains(t)) a.switchTo(t);
        });
        return box;
    }

    /** The top of a tab's picture, as wide as the card. */
    private final class Thumb extends View {
        private final Tab t;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();

        Thumb(Tab t) {
            super(a);
            this.t = t;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            Bitmap b = t.thumb;
            if (b == null) {
                c.drawColor(a.getColor(R.color.field_bg));
                paint.setColor(a.getColor(R.color.text_secondary));
                paint.setTextSize(App.dp(13));
                paint.setTextAlign(Paint.Align.CENTER);
                String host = t.isHome() ? a.getString(R.string.new_tab) : Urls.host(t.address());
                c.drawText(TextUtils.ellipsize(host, new TextPaint(paint), w - App.dp(16), TextUtils.TruncateAt.END).toString(),
                        w / 2f, h / 2f, paint);
                return;
            }
            c.drawColor(0xFFFFFFFF);
            float s = (float) w / b.getWidth();
            src.set(0, 0, b.getWidth(), Math.min(b.getHeight(), Math.round(h / s)));
            dst.set(0, 0, w, src.height() * s);
            c.drawBitmap(b, src, dst, paint);
        }
    }

    // ------------------------------------------------------------------ carousel

    /** The finger has begun to move sideways on the bar: the page of tab {@code from} becomes a card. */
    void swipeBegin(int from) {
        if (carousel != null || grid != null || from < 0) return;
        a.snapshot();
        carousel = new Carousel(new ArrayList<>(a.tabs), from);
        layer.addView(carousel, 1, new FrameLayout.LayoutParams(-1, -1));
    }

    /** @param fast the tab button: a short way of the finger per tab, so a small swipe crosses many */
    void swipeMove(float dx, boolean fast) {
        if (carousel != null) carousel.move(dx, fast);
    }

    void swipeEnd(boolean cancelled) {
        if (carousel != null) carousel.settle(cancelled);
    }

    private static float lerp(float x, float y, float t) {
        return x + (y - x) * t;
    }

    private final class Carousel extends View {
        /** A card at rest is this much of the page. */
        private static final float REST = 0.7f;
        private final List<Tab> tabs;
        private final int from;
        /** Which card is in the middle (between two while moving), and how far the page has become a card (0..1). */
        private float pos, open;
        private final RectF page = new RectF(), card = new RectF(), part = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final HashMap<Bitmap, BitmapShader> shaders = new HashMap<>();
        private final HashMap<Tab, CharSequence> labels = new HashMap<>();
        private final Rect iconSrc = new Rect();
        private ValueAnimator anim;
        private boolean settling;
        private long lastAt;
        private float lastPos, speed;
        private final int back = 0xFFE3E7E4;

        Carousel(List<Tab> tabs, int from) {
            super(a);
            this.tabs = tabs;
            this.from = from;
            pos = from;
            setClickable(true);
            text.setTextSize(App.dp(13.5f));
            run(0f, 1f, 190, false, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            int[] me = new int[2], it = new int[2];
            getLocationInWindow(me);
            content.getLocationInWindow(it);
            page.set(it[0] - me[0], it[1] - me[1], it[0] - me[0] + content.getWidth(), it[1] - me[1] + content.getHeight());
        }

        private float restPitch() {
            return page.width() * REST + App.dp(22);
        }

        /** Animates {@code open} (or {@code pos} when {@code scroll}) and runs {@code then} at the end. */
        private void run(float fromV, float toV, long ms, final boolean scroll, final Runnable then) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(fromV, toV);
            anim.setDuration(ms);
            anim.setInterpolator(new DecelerateInterpolator(1.6f));
            anim.addUpdateListener(v -> {
                if (scroll) pos = (float) v.getAnimatedValue();
                else open = (float) v.getAnimatedValue();
                invalidate();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!cancelled && then != null) then.run();
                }
            });
            anim.start();
        }

        void move(float dx, boolean fast) {
            if (settling) return;
            float max = tabs.size() - 1, to = from - dx / (fast ? App.dp(44) : restPitch());
            // past the first or the last card the row only gives a little
            if (to < 0) to *= 0.3f;
            else if (to > max) to = max + (to - max) * 0.3f;
            long now = android.os.SystemClock.uptimeMillis();
            if (lastAt != 0 && now > lastAt) speed = speed * 0.4f + 0.6f * (to - lastPos) * 1000f / (now - lastAt);
            lastAt = now;
            lastPos = to;
            pos = to;
            invalidate();
        }

        /** The finger has lifted: glide to the card the row was heading for, then let it grow into the page. */
        void settle(boolean cancelled) {
            if (settling) return;
            settling = true;
            // a finger that rested before lifting throws nothing
            if (android.os.SystemClock.uptimeMillis() - lastAt > 120) speed = 0;
            int max = tabs.size() - 1;
            final int target = cancelled ? from : Math.max(0, Math.min(max, Math.round(pos + speed * 0.16f)));
            long ms = Math.max(150, Math.min(420, Math.round(140 + 90 * Math.abs(target - pos))));
            run(pos, target, ms, true, () -> {
                Tab t = tabs.get(target);
                if (target != from && a.tabs.contains(t)) a.switchTo(t);
                run(open, 0f, 210, false, this::remove);
            });
        }

        private void remove() {
            if (carousel == this) carousel = null;
            layer.removeView(this);
            release();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (page.isEmpty()) return;
            paint.setShader(null);
            paint.setColor(back);
            paint.setAlpha(Math.round(255 * Math.min(1f, open * 1.6f)));
            c.drawRect(0, 0, w, h, paint);

            float cw = lerp(page.width(), page.width() * REST, open), ch = lerp(page.height(), page.height() * REST, open);
            float cx = lerp(page.centerX(), w / 2f, open), cy = lerp(page.centerY(), h / 2f + App.dp(8), open);
            float pitch = cw + App.dp(22), radius = App.dp(22) * open;
            int first = Math.max(0, (int) Math.floor(pos - (cx + cw / 2) / pitch - 1));
            int last = Math.min(tabs.size() - 1, (int) Math.ceil(pos + (w - cx + cw / 2) / pitch + 1));
            for (int i = first; i <= last; i++) {
                float x = cx + (i - pos) * pitch;
                card.set(x - cw / 2, cy - ch / 2, x + cw / 2, cy + ch / 2);
                if (card.right < 0 || card.left > w) continue;
                Tab t = tabs.get(i);
                want(t, this);
                drawCard(c, t, radius);
                if (open > 0.05f) drawLabel(c, t);
            }
            // where in the row: "3 / 7"
            text.setColor(a.getColor(R.color.text_secondary));
            text.setAlpha(Math.round(255 * open));
            text.setTypeface(Typeface.DEFAULT);
            text.setTextAlign(Paint.Align.CENTER);
            int at = Math.max(0, Math.min(tabs.size() - 1, Math.round(pos)));
            c.drawText((at + 1) + " / " + tabs.size(), w / 2f, Math.min(h - App.dp(18), cy + ch / 2 + App.dp(34)), text);
        }

        private void drawCard(Canvas c, Tab t, float radius) {
            paint.setShader(null);
            // a soft edge instead of a shadow layer, which hardware canvases only draw for text on old versions
            for (int k = 3; k >= 1 && open > 0.02f; k--) {
                paint.setColor(0x10000000);
                float d = k * App.dp(1.5f);
                c.drawRoundRect(card.left - d, card.top - d + App.dp(2), card.right + d, card.bottom + d + App.dp(2),
                        radius + d, radius + d, paint);
            }
            paint.setColor(0xFFFFFFFF);
            c.drawRoundRect(card, radius, radius, paint);
            Bitmap b = t.thumb;
            if (b == null) {
                text.setColor(a.getColor(R.color.text_secondary));
                text.setAlpha(255);
                text.setTypeface(Typeface.DEFAULT);
                text.setTextAlign(Paint.Align.CENTER);
                String host = t.isHome() ? a.getString(R.string.new_tab) : Urls.host(t.address());
                c.drawText(TextUtils.ellipsize(host, text, card.width() - App.dp(24), TextUtils.TruncateAt.END).toString(),
                        card.centerX(), card.centerY(), text);
                return;
            }
            BitmapShader sh = shaders.get(b);
            if (sh == null) {
                sh = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shaders.put(b, sh);
            }
            float s = card.width() / b.getWidth();
            matrix.setScale(s, s);
            matrix.postTranslate(card.left, card.top);
            sh.setLocalMatrix(matrix);
            paint.setShader(sh);
            float bottom = card.top + b.getHeight() * s;
            if (bottom >= card.bottom - 1) c.drawRoundRect(card, radius, radius, paint);
            else {
                // a picture shorter than the card (taken in another orientation): its last row must not smear down
                c.save();
                part.set(card.left, card.top, card.right, bottom);
                c.clipRect(part);
                c.drawRoundRect(card, radius, radius, paint);
                c.restore();
            }
            paint.setShader(null);
        }

        /** Site icon and title over the card, as recent apps name theirs. */
        private void drawLabel(Canvas c, Tab t) {
            CharSequence label = labels.get(t);
            int icon = App.dp(18), gap = App.dp(7);
            if (label == null) {
                text.setTypeface(Typeface.DEFAULT_BOLD);
                label = TextUtils.ellipsize(t.label(), text, Math.max(App.dp(40), page.width() * REST - icon - gap - App.dp(12)),
                        TextUtils.TruncateAt.END);
                labels.put(t, label);
            }
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(a.getColor(R.color.text_primary));
            text.setAlpha(Math.round(255 * open));
            float tw = text.measureText(label, 0, label.length());
            Bitmap fav = iconOf(t);
            float total = tw + (fav == null ? 0 : icon + gap), x = card.centerX() - total / 2, base = card.top - App.dp(13);
            if (fav != null) {
                iconSrc.set(0, 0, fav.getWidth(), fav.getHeight());
                part.set(x, base - icon + App.dp(3), x + icon, base + App.dp(3));
                paint.setAlpha(Math.round(255 * open));
                c.drawBitmap(fav, iconSrc, part, paint);
                paint.setAlpha(255);
                x += icon + gap;
            }
            c.drawText(label, 0, label.length(), x, base, text);
        }
    }
}
