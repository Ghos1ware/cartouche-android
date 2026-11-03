package com.limelight.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Cartouche's connection screen, over the stream while it starts, in place of Moonlight's
 * dialog: the game's cover (blurred behind, as a card on the right), its name, the PC, the step
 * in plain words and a bar in Cartouche's colors. Same calls as the dialog it replaces:
 * setMessage (Moonlight's stage names) and dismiss.
 */
public class ConnectingScreen {
    private static final int BG = 0xFF07060D;
    private static final int INK = 0xFFF6F3FF;
    private static final int MUTED = 0xA8F6F3FF;
    private static final int BRAND = 0xFF8B7BFF;
    private static final int BRAND_2 = 0xFFD36BFF;

    /** Moonlight's stages in their order (moonlight-common-c), for the bar. */
    private static final List<String> STAGES = Arrays.asList(
            "platform initialization", "name resolution", "audio stream initialization", "RTSP handshake",
            "control stream initialization", "video stream initialization", "input stream initialization",
            "control stream establishment", "video stream establishment", "audio stream establishment",
            "input stream establishment");

    private final Activity activity;
    private final FrameLayout root;
    private final TextView step;
    private final View fill;
    private final View track;
    private final String appName;
    private final String pcName;
    private float progress = 0.04f;
    private boolean gone;

    /** Where CartoucheActivity keeps the covers it reads from a PC: cache/covers/<uuid>-<appId>. */
    public static File coverFile(File cacheDir, String pcUuid, int appId) {
        return new File(new File(cacheDir, "covers"), pcUuid + "-" + appId);
    }

    public static ConnectingScreen show(Activity activity, String appName, String pcName, File cover) {
        ConnectingScreen screen = new ConnectingScreen(activity, appName, pcName, cover);
        activity.runOnUiThread(screen::attach);
        return screen;
    }

    private ConnectingScreen(Activity activity, String appName, String pcName, File cover) {
        this.activity = activity;
        this.appName = appName != null ? appName : "";
        this.pcName = pcName != null ? pcName : "";
        Bitmap art = cover != null && cover.exists() ? BitmapFactory.decodeFile(cover.getPath()) : null;

        root = new FrameLayout(activity);
        root.setBackgroundColor(BG);
        root.setClickable(true);

        // The cover, blurred and dark, behind everything.
        if (art != null) {
            ImageView back = new ImageView(activity);
            back.setImageBitmap(art);
            back.setScaleType(ImageView.ScaleType.CENTER_CROP);
            back.setAlpha(0.42f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                back.setRenderEffect(RenderEffect.createBlurEffect(dp(38), dp(38), Shader.TileMode.CLAMP));
            }
            root.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // Cartouche's glow, and the dark that keeps the text readable.
        View glow = new View(activity);
        GradientDrawable radial = new GradientDrawable();
        radial.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        radial.setColors(new int[] {0x668B7BFF, 0x00000000});
        radial.setGradientCenter(0.18f, 0.85f);
        radial.setGradientRadius(dp(520));
        glow.setBackground(radial);
        root.addView(glow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF207060D, 0xB307060D, 0x6607060D}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(56), dp(28), dp(56), dp(28));
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView kicker = label("CARTOUCHE  ·  STREAMING", 11, BRAND, Typeface.create("sans-serif-medium", Typeface.BOLD));
        kicker.setLetterSpacing(0.22f);
        text.addView(kicker);

        TextView title = label(this.appName, 38, INK, Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setLineSpacing(0, 0.92f);
        title.setPadding(0, dp(10), 0, 0);
        text.addView(title);

        TextView from = label(this.pcName.isEmpty() ? "" : activity.getString(R.string.cartouche_connecting_from, this.pcName), 15, MUTED, Typeface.create("sans-serif", Typeface.NORMAL));
        from.setPadding(0, dp(6), 0, 0);
        text.addView(from);

        // The bar: a quiet track, a gradient that moves on with each stage.
        FrameLayout bar = new FrameLayout(activity);
        bar.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(360), dp(6));
        barParams.topMargin = dp(30);
        text.addView(bar, barParams);
        track = new View(activity);
        GradientDrawable trackShape = new GradientDrawable();
        trackShape.setColor(0x24F6F3FF);
        trackShape.setCornerRadius(dp(3));
        track.setBackground(trackShape);
        bar.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fill = new View(activity);
        GradientDrawable fillShape = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {BRAND, BRAND_2});
        fillShape.setCornerRadius(dp(3));
        fill.setBackground(fillShape);
        bar.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

        step = label(activity.getString(R.string.cartouche_connecting_step_pc, this.pcName.isEmpty() ? "PC" : this.pcName), 14, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        step.setPadding(0, dp(12), 0, 0);
        text.addView(step);

        // The cover as a card, or the cartridge without one.
        ImageView card = new ImageView(activity);
        card.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (art != null) {
            card.setImageBitmap(art);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                }
            });
            card.setClipToOutline(true);
            card.setElevation(dp(24));
        } else {
            card.setImageResource(R.mipmap.ic_launcher_foreground);
            card.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(200), dp(300));
        cardParams.leftMargin = dp(40);
        row.addView(card, cardParams);

        root.setAlpha(0f);
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

    private void attach() {
        if (gone || activity.isFinishing()) {
            return;
        }
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(root,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.animate().alpha(1f).setDuration(220).start();
        track.post(() -> setProgress(progress));
    }

    private void setProgress(float value) {
        progress = Math.max(progress, value);
        final int from = fill.getLayoutParams().width;
        final int to = Math.round(track.getWidth() * progress);
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(420);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            ViewGroup.LayoutParams params = fill.getLayoutParams();
            params.width = (int) a.getAnimatedValue();
            fill.setLayoutParams(params);
        });
        animator.start();
    }

    /** Moonlight's message: "Starting <stage>"; the stage, in Cartouche's words, moves the bar. */
    public void setMessage(final String message) {
        activity.runOnUiThread(() -> {
            String lower = message != null ? message.toLowerCase() : "";
            int index = -1;
            for (int i = 0; i < STAGES.size(); i++) {
                if (lower.contains(STAGES.get(i).toLowerCase())) {
                    index = i;
                }
            }
            if (index < 0) {
                return;
            }
            String pc = pcName.isEmpty() ? "PC" : pcName;
            int words = index <= 3 ? R.string.cartouche_connecting_step_pc
                    : index <= 6 ? R.string.cartouche_connecting_step_picture
                    : R.string.cartouche_connecting_step_game;
            step.setText(activity.getString(words, words == R.string.cartouche_connecting_step_game ? appName : pc));
            if (track.getWidth() > 0) {
                setProgress((index + 1f) / (STAGES.size() + 1f));
            } else {
                progress = Math.max(progress, (index + 1f) / (STAGES.size() + 1f));
            }
        });
    }

    public void dismiss() {
        activity.runOnUiThread(() -> {
            gone = true;
            if (root.getParent() == null) {
                return;
            }
            setProgress(1f);
            root.animate().alpha(0f).setStartDelay(180).setDuration(260).withEndAction(() -> {
                if (root.getParent() != null) {
                    ((ViewGroup) root.getParent()).removeView(root);
                }
            }).start();
        });
    }
}
