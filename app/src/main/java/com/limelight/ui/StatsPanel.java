package com.limelight.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.video.PerfStats;

import java.util.Locale;

/**
 * Cartouche's stats over the stream, in place of Moonlight's block of text: the frame rate large,
 * then the network, the phone's decoding, the PC's own time and the frames lost, each with a dot
 * that says whether it is fine. It closes to a small round button in its corner, which opens it
 * again; both move under the finger, and the corner they sit in and whether it is open are kept.
 */
public class StatsPanel {
    private static final int GLASS = 0xE007060D;
    private static final int EDGE = 0x24FFFFFF;
    private static final int INK = 0xFFF6F3FF;
    private static final int MUTED = 0x99F6F3FF;
    private static final int BRAND = 0xFF8B7BFF;
    private static final int BRAND_2 = 0xFFD36BFF;
    private static final int GOOD = 0xFF4ADE80;
    private static final int FAIR = 0xFFFBBF24;
    private static final int POOR = 0xFFF87171;

    private static final String PREFS = "cartouche_stats";

    private final Activity activity;
    private final FrameLayout parent;
    private final SharedPreferences prefs;
    private final LinearLayout card;
    private final View pill;
    private final TextView fps;
    private final TextView size;
    private final TextView codec;
    private final View live;
    private final Metric network;
    private final Metric decode;
    private final Metric host;
    private final Metric loss;
    private boolean open;
    private boolean shown = true;
    // The corner both sit against, as a share of the screen (0..1), and which corner it is.
    private float anchorX;
    private float anchorY;

    private static final class Metric {
        final TextView value;
        final View dot;

        Metric(TextView value, View dot) {
            this.value = value;
            this.dot = dot;
        }
    }

    public StatsPanel(Activity activity, FrameLayout parent) {
        this.activity = activity;
        this.parent = parent;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        open = prefs.getBoolean("open", true);
        anchorX = prefs.getFloat("x", 0f);
        anchorY = prefs.getFloat("y", 0f);

        // ---- The card ----
        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(8), dp(12));
        card.setBackground(glass(dp(16)));
        card.setElevation(dp(10));

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        live = dot(GOOD, 6);
        head.addView(live);
        TextView kicker = text(activity.getString(R.string.cartouche_stats_title), 10, BRAND, Typeface.create("sans-serif-medium", Typeface.BOLD));
        kicker.setLetterSpacing(0.2f);
        kicker.setPadding(dp(7), 0, 0, 0);
        head.addView(kicker, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 13, MUTED, Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(activity.getString(R.string.cartouche_stats_close));
        close.setOnClickListener(v -> setOpen(false));
        head.addView(close, new LinearLayout.LayoutParams(dp(30), dp(26)));
        card.addView(head);

        LinearLayout headline = new LinearLayout(activity);
        headline.setOrientation(LinearLayout.HORIZONTAL);
        headline.setGravity(Gravity.BOTTOM);
        headline.setPadding(0, dp(4), dp(6), 0);
        fps = text("--", 30, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        fps.setFontFeatureSettings("tnum");
        headline.addView(fps);
        TextView unit = text(activity.getString(R.string.cartouche_stats_fps), 12, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        unit.setPadding(dp(4), 0, 0, dp(5));
        headline.addView(unit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout tags = new LinearLayout(activity);
        tags.setOrientation(LinearLayout.VERTICAL);
        tags.setGravity(Gravity.END);
        tags.setPadding(0, 0, 0, dp(4));
        size = text("", 11, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        size.setFontFeatureSettings("tnum");
        tags.addView(size);
        codec = text("", 10, INK, Typeface.create("sans-serif-medium", Typeface.BOLD));
        codec.setLetterSpacing(0.08f);
        codec.setPadding(dp(7), dp(2), dp(7), dp(2));
        GradientDrawable chip = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0x668B7BFF, 0x66D36BFF});
        chip.setCornerRadius(dp(8));
        codec.setBackground(chip);
        LinearLayout.LayoutParams codecParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codecParams.topMargin = dp(3);
        codecParams.gravity = Gravity.END;
        tags.addView(codec, codecParams);
        headline.addView(tags);
        card.addView(headline);

        View rule = new View(activity);
        rule.setBackgroundColor(EDGE);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ruleParams.setMargins(0, dp(8), dp(6), dp(8));
        card.addView(rule, ruleParams);

        LinearLayout rowA = row();
        network = metric(rowA, R.string.cartouche_stats_network);
        decode = metric(rowA, R.string.cartouche_stats_decode);
        card.addView(rowA);
        LinearLayout rowB = row();
        rowB.setPadding(0, dp(8), 0, 0);
        host = metric(rowB, R.string.cartouche_stats_host);
        loss = metric(rowB, R.string.cartouche_stats_loss);
        card.addView(rowB);

        // ---- The round button that opens it again ----
        pill = new Bars(activity);
        pill.setBackground(glass(dp(20)));
        pill.setElevation(dp(8));
        pill.setAlpha(0.85f);
        pill.setContentDescription(activity.getString(R.string.cartouche_stats_open));

        card.setOnTouchListener(drag(card, null));
        pill.setOnTouchListener(drag(pill, () -> setOpen(true)));

        parent.addView(card, new FrameLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(pill, new FrameLayout.LayoutParams(dp(40), dp(40)));
        card.setVisibility(View.INVISIBLE);
        pill.setVisibility(View.INVISIBLE);
        // Placed once their sizes are known, and again when the screen turns.
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place());
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                place();
            }
        });
        parent.post(this::place);
    }

    /** Over what was added to the screen after it (the on-screen controller). */
    public void bringToFront() {
        card.bringToFront();
        pill.bringToFront();
    }

    /** Hidden in picture-in-picture, back after. */
    public void setShown(boolean shown) {
        this.shown = shown;
        place();
    }

    public void update(final PerfStats stats) {
        activity.runOnUiThread(() -> {
            Locale locale = Locale.getDefault();
            fps.setText(String.format(locale, "%.0f", stats.renderedFps));
            size.setText(stats.width + "×" + stats.height);
            codec.setText(codecOf(stats.decoder));
            boolean smooth = stats.renderedFps >= stats.totalFps * 0.95f;
            tint(live, smooth && stats.lossPercent < 1 ? GOOD : smooth ? FAIR : POOR);
            network.value.setText(stats.rttVarianceMs > 0
                    ? String.format(locale, "%d ms ±%d", stats.rttMs, stats.rttVarianceMs)
                    : String.format(locale, "%d ms", stats.rttMs));
            tint(network.dot, grade(stats.rttMs, 20, 50));
            decode.value.setText(String.format(locale, "%.1f ms", stats.decodeMs));
            tint(decode.dot, grade(stats.decodeMs, 12, 20));
            if (stats.hostLatencyMs >= 0) {
                host.value.setText(String.format(locale, "%.1f ms", stats.hostLatencyMs));
                tint(host.dot, grade(stats.hostLatencyMs, 8, 16));
            } else {
                host.value.setText("—");
                tint(host.dot, MUTED);
            }
            loss.value.setText(String.format(locale, "%.1f %%", stats.lossPercent));
            tint(loss.dot, grade(stats.lossPercent, 1, 5));
        });
    }

    private static int grade(float value, float fine, float fair) {
        return value < fine ? GOOD : value < fair ? FAIR : POOR;
    }

    private static String codecOf(String decoder) {
        String name = decoder != null ? decoder.toLowerCase(Locale.ROOT) : "";
        String format = name.contains("av1") || name.contains("av01") ? "AV1"
                : name.contains("hevc") || name.contains("h265") ? "HEVC"
                : name.contains("avc") || name.contains("h264") ? "H.264" : "?";
        boolean software = name.startsWith("omx.google") || name.startsWith("c2.android");
        return software ? format + " · CPU" : format;
    }

    private void setOpen(boolean value) {
        open = value;
        prefs.edit().putBoolean("open", value).apply();
        place();
    }

    // ---- Where they sit ----

    /** Puts the open one against the kept corner, inside the screen. */
    private void place() {
        int width = parent.getWidth();
        int height = parent.getHeight();
        card.setVisibility(shown && open ? View.VISIBLE : View.GONE);
        pill.setVisibility(shown && !open ? View.VISIBLE : View.GONE);
        if (width == 0 || height == 0) {
            return;
        }
        View view = open ? card : pill;
        int w = view.getWidth() > 0 ? view.getWidth() : view.getLayoutParams().width;
        int h = view.getHeight() > 0 ? view.getHeight() : dp(40);
        float px = anchorX * width;
        float py = anchorY * height;
        // The corner nearest the anchor's side: a panel on the right grows to the left.
        float x = anchorX < 0.5f ? px : px - w;
        float y = anchorY < 0.5f ? py : py - h;
        view.setX(clamp(x, dp(10), width - w - dp(10)));
        view.setY(clamp(y, dp(10), height - h - dp(10)));
    }

    /** After a drag: the corner of the view nearest its side of the screen becomes the anchor. */
    private void keep(View view) {
        int width = parent.getWidth();
        int height = parent.getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        float centerX = view.getX() + view.getWidth() / 2f;
        float centerY = view.getY() + view.getHeight() / 2f;
        float px = centerX < width / 2f ? view.getX() : view.getX() + view.getWidth();
        float py = centerY < height / 2f ? view.getY() : view.getY() + view.getHeight();
        anchorX = clamp(px / width, 0f, 1f);
        anchorY = clamp(py / height, 0f, 1f);
        // Exactly on the middle would flip sides: keep it a hair to the side it came from.
        if (anchorX == 0.5f) anchorX = 0.499f;
        if (anchorY == 0.5f) anchorY = 0.499f;
        prefs.edit().putFloat("x", anchorX).putFloat("y", anchorY).apply();
        place();
    }

    private View.OnTouchListener drag(final View view, final Runnable tap) {
        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        return new View.OnTouchListener() {
            float downX, downY, startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = view.getX();
                        startY = view.getY();
                        moved = false;
                        view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) > slop) {
                            moved = true;
                        }
                        if (moved) {
                            view.setX(clamp(startX + dx, 0, parent.getWidth() - view.getWidth()));
                            view.setY(clamp(startY + dy, 0, parent.getHeight() - view.getHeight()));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        if (moved) {
                            keep(view);
                        } else if (tap != null && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            tap.run();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

    // ---- Pieces ----

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private Metric metric(LinearLayout row, int label) {
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(activity.getString(label).toUpperCase(Locale.getDefault()), 9, MUTED, Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setLetterSpacing(0.12f);
        cell.addView(name);
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(2), 0, 0);
        View dot = dot(MUTED, 6);
        line.addView(dot);
        TextView value = text("—", 14, INK, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setFontFeatureSettings("tnum");
        value.setPadding(dp(6), 0, 0, 0);
        line.addView(value);
        cell.addView(line);
        row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return new Metric(value, dot);
    }

    private View dot(int color, int size) {
        View dot = new View(activity);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        dot.setBackground(shape);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return dot;
    }

    private static void tint(View dot, int color) {
        ((GradientDrawable) dot.getBackground()).setColor(color);
    }

    private GradientDrawable glass(int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(GLASS);
        shape.setCornerRadius(radius);
        shape.setStroke(Math.max(1, dp(1)), EDGE);
        return shape;
    }

    private TextView text(String value, int sp, int color, Typeface face) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The round button's sign: three bars in Cartouche's gradient. */
    private static final class Bars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();

        Bars(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float unit = w / 40f;
            paint.setShader(new LinearGradient(0, h * 0.7f, w, h * 0.3f, BRAND, BRAND_2, Shader.TileMode.CLAMP));
            float[] heights = {7, 12, 17};
            float barWidth = 4 * unit;
            float gap = 3 * unit;
            float left = (w - (3 * barWidth + 2 * gap)) / 2f;
            float base = h / 2f + 8.5f * unit;
            for (int i = 0; i < 3; i++) {
                bar.set(left + i * (barWidth + gap), base - heights[i] * unit, left + i * (barWidth + gap) + barWidth, base);
                canvas.drawRoundRect(bar, 1.5f * unit, 1.5f * unit, paint);
            }
        }
    }
}
