package com.limelight.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/**
 * What CartoucheActivity shows while its page loads, native so it shows without the network:
 * Cartouche's icon on its night, a bar of light that runs, then, when the site does not answer,
 * what happened and « Réessayer ». It fades away once the page is there.
 */
public class LoadingScreen {
    private static final int BG = 0xFF07060D;
    private static final int INK = 0xFFF6F3FF;
    private static final int MUTED = 0xA8F6F3FF;
    private static final int BRAND = 0xFF8B7BFF;
    private static final int BRAND_2 = 0xFFD36BFF;

    private final Activity activity;
    private final FrameLayout root;
    private final View bar;
    private final View light;
    private final LinearLayout problem;
    private final TextView title;
    private final TextView detail;
    private ValueAnimator running;

    public LoadingScreen(Activity activity, Runnable retry) {
        this.activity = activity;
        root = new FrameLayout(activity);
        root.setBackgroundColor(BG);
        root.setClickable(true);

        // A glow under the icon.
        View glow = new View(activity);
        GradientDrawable radial = new GradientDrawable();
        radial.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        radial.setColors(new int[] {0x448B7BFF, 0x00000000});
        radial.setGradientRadius(dp(260));
        glow.setBackground(radial);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(32), 0, dp(32), 0);
        FrameLayout.LayoutParams centred = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(column, centred);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher_foreground);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        column.addView(icon, new LinearLayout.LayoutParams(dp(140), dp(140)));

        TextView name = label("CARTOUCHE", 15, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        name.setLetterSpacing(0.28f);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(6);
        column.addView(name, nameParams);

        // The bar: a quiet track, a gradient that runs along it.
        bar = new FrameLayout(activity);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x1FF6F3FF);
        track.setCornerRadius(dp(2));
        bar.setBackground(track);
        bar.setClipToOutline(true);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(150), dp(3));
        barParams.topMargin = dp(26);
        column.addView(bar, barParams);
        light = new View(activity);
        GradientDrawable sweep = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0x008B7BFF, BRAND, BRAND_2, 0x00D36BFF});
        sweep.setCornerRadius(dp(2));
        light.setBackground(sweep);
        ((FrameLayout) bar).addView(light, new FrameLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.MATCH_PARENT));

        // What went wrong, and the way out.
        problem = new LinearLayout(activity);
        problem.setOrientation(LinearLayout.VERTICAL);
        problem.setGravity(Gravity.CENTER_HORIZONTAL);
        problem.setVisibility(View.GONE);
        LinearLayout.LayoutParams problemParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        problemParams.topMargin = dp(28);
        column.addView(problem, problemParams);
        title = label("", 20, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        problem.addView(title);
        detail = label("", 14, MUTED, Typeface.create("sans-serif", Typeface.NORMAL));
        detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(0, 1.3f);
        detail.setPadding(0, dp(10), 0, 0);
        problem.addView(detail);
        TextView again = label(activity.getString(R.string.cartouche_loading_retry), 15, 0xFFFFFFFF, Typeface.create("sans-serif-medium", Typeface.BOLD));
        again.setGravity(Gravity.CENTER);
        again.setPadding(dp(28), dp(14), dp(28), dp(14));
        GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {BRAND, BRAND_2});
        pill.setCornerRadius(dp(16));
        again.setBackground(pill);
        again.setElevation(dp(6));
        again.setOnClickListener(v -> retry.run());
        LinearLayout.LayoutParams againParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        againParams.topMargin = dp(22);
        problem.addView(again, againParams);
    }

    public View view() {
        return root;
    }

    /** Loading: the bar runs. */
    public void loading() {
        root.animate().cancel();
        root.setAlpha(1f);
        root.setVisibility(View.VISIBLE);
        problem.setVisibility(View.GONE);
        bar.setVisibility(View.VISIBLE);
        if (running == null) {
            running = ValueAnimator.ofFloat(-1f, 1f);
            running.setDuration(1100);
            running.setRepeatCount(ValueAnimator.INFINITE);
            running.setInterpolator(new AccelerateDecelerateInterpolator());
            running.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                // From just before the track to just past it.
                light.setTranslationX((t + 1f) / 2f * (dp(150) + dp(60)) - dp(60));
            });
        }
        if (!running.isStarted()) {
            running.start();
        }
    }

    /** The site did not answer (or is slow to): what happened, and « Réessayer ». */
    public void failed(boolean slow) {
        root.animate().cancel();
        root.setAlpha(1f);
        root.setVisibility(View.VISIBLE);
        title.setText(slow ? R.string.cartouche_loading_slow_title : R.string.cartouche_loading_error_title);
        detail.setText(slow ? R.string.cartouche_loading_slow : R.string.cartouche_loading_error);
        problem.setVisibility(View.VISIBLE);
        // Slow: the bar keeps running, the page may still come.
        bar.setVisibility(slow ? View.VISIBLE : View.GONE);
        if (!slow && running != null) {
            running.cancel();
        }
    }

    /** The page is there: the screen fades away. */
    public void done() {
        if (root.getVisibility() != View.VISIBLE) {
            return;
        }
        root.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            root.setVisibility(View.GONE);
            if (running != null) {
                running.cancel();
            }
        }).start();
    }

    private TextView label(String value, int sp, int color, Typeface face) {
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
}
