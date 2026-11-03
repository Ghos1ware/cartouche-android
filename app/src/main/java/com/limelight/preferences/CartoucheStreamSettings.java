package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.preference.PreferenceManager;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stream settings Cartouche's own screen shows (CartoucheActivity's "streamSettings" and
 * "setStreamSetting"): Moonlight's preferences, the ones a player looks for, read and written
 * where Moonlight reads them. Only these keys, with their types and Moonlight's defaults; every
 * other one stays in Moonlight's own settings screen. Values travel as strings.
 */
public class CartoucheStreamSettings {
    private enum Kind { BOOL, INT, STRING }

    private static final class Key {
        final Kind kind;
        final String fallback;

        Key(Kind kind, String fallback) {
            this.kind = kind;
            this.fallback = fallback;
        }
    }

    private static final Map<String, Key> KEYS = new LinkedHashMap<>();
    /** The choices of the lists (Moonlight's entry values). */
    private static final Map<String, String> CHOICES = new LinkedHashMap<>();

    static {
        KEYS.put(PreferenceConfiguration.RESOLUTION_PREF_STRING, new Key(Kind.STRING, PreferenceConfiguration.DEFAULT_RESOLUTION));
        KEYS.put(PreferenceConfiguration.FPS_PREF_STRING, new Key(Kind.STRING, PreferenceConfiguration.DEFAULT_FPS));
        // Absent: Moonlight's own, from the resolution and the frame rate ("auto" here).
        KEYS.put(PreferenceConfiguration.BITRATE_PREF_STRING, new Key(Kind.INT, "auto"));
        KEYS.put("video_format", new Key(Kind.STRING, "auto"));
        KEYS.put("checkbox_enable_hdr", new Key(Kind.BOOL, "false"));
        KEYS.put("frame_pacing", new Key(Kind.STRING, "latency"));
        KEYS.put("checkbox_stretch_video", new Key(Kind.BOOL, "false"));
        KEYS.put("checkbox_enable_perf_overlay", new Key(Kind.BOOL, "false"));
        KEYS.put("list_audio_config", new Key(Kind.STRING, "2"));
        KEYS.put("checkbox_host_audio", new Key(Kind.BOOL, "false"));
        KEYS.put("checkbox_touchscreen_trackpad", new Key(Kind.BOOL, "false"));
        KEYS.put("checkbox_show_onscreen_controls", new Key(Kind.BOOL, "false"));
        KEYS.put("checkbox_vibrate_osc", new Key(Kind.BOOL, "true"));
        KEYS.put("checkbox_flip_face_buttons", new Key(Kind.BOOL, "false"));
        KEYS.put("checkbox_vibrate_fallback", new Key(Kind.BOOL, "false"));
        KEYS.put("checkbox_multi_controller", new Key(Kind.BOOL, "true"));
        KEYS.put("checkbox_enable_sops", new Key(Kind.BOOL, "true"));
        KEYS.put("checkbox_enable_pip", new Key(Kind.BOOL, "false"));
        CHOICES.put("video_format", "auto|forceav1|forceh265|neverh265");
        CHOICES.put("frame_pacing", "latency|balanced|cap-fps|smoothness");
        CHOICES.put("list_audio_config", "2|51|71");
    }

    /** The values, the phone's own screen size and refresh rate, and Moonlight's bitrate for them. */
    public static JSONObject read(Context context) throws JSONException {
        // Puts the phone's own resolution in place the first time, as a stream would.
        PreferenceConfiguration.readPreferences(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        JSONObject values = new JSONObject();
        for (Map.Entry<String, Key> entry : KEYS.entrySet()) {
            String key = entry.getKey();
            Key spec = entry.getValue();
            String value;
            if (!prefs.contains(key)) {
                value = spec.fallback;
            } else if (spec.kind == Kind.BOOL) {
                value = String.valueOf(prefs.getBoolean(key, Boolean.parseBoolean(spec.fallback)));
            } else if (spec.kind == Kind.INT) {
                value = String.valueOf(prefs.getInt(key, 0));
            } else {
                value = prefs.getString(key, spec.fallback);
            }
            values.put(key, value);
        }
        JSONObject result = new JSONObject();
        result.put("values", values);
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        result.put("screen", (Math.max(size.x, size.y) & ~1) + "x" + (Math.min(size.x, size.y) & ~1));
        result.put("refreshRate", Math.round(display.getRefreshRate()));
        result.put("autoBitrate", PreferenceConfiguration.getDefaultBitrate(
                prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION),
                prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS)));
        return result;
    }

    public static JSONObject set(Context context, String key, String value) throws JSONException {
        Key spec = KEYS.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("Not a stream setting: " + key);
        }
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        switch (spec.kind) {
            case BOOL:
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new IllegalArgumentException("On or off: " + value);
                }
                editor.putBoolean(key, Boolean.parseBoolean(value));
                break;
            case INT:
                if ("auto".equals(value)) {
                    editor.remove(key);
                } else {
                    int kbps = Integer.parseInt(value);
                    if (kbps < 500 || kbps > 150000) {
                        throw new IllegalArgumentException("Bitrate out of range: " + value);
                    }
                    editor.putInt(key, kbps);
                }
                break;
            default:
                if (key.equals(PreferenceConfiguration.RESOLUTION_PREF_STRING) && !value.matches("\\d{3,4}x\\d{3,4}")) {
                    throw new IllegalArgumentException("Not a resolution: " + value);
                }
                if (key.equals(PreferenceConfiguration.FPS_PREF_STRING) && !value.matches("30|60|90|120|144")) {
                    throw new IllegalArgumentException("Not a frame rate: " + value);
                }
                String allowed = CHOICES.get(key);
                if (allowed != null && !value.matches(allowed)) {
                    throw new IllegalArgumentException("Not a choice of " + key + ": " + value);
                }
                editor.putString(key, value);
                // As Moonlight's screen does: a new size or frame rate, its own bitrate again.
                if (key.equals(PreferenceConfiguration.RESOLUTION_PREF_STRING) || key.equals(PreferenceConfiguration.FPS_PREF_STRING)) {
                    editor.remove(PreferenceConfiguration.BITRATE_PREF_STRING);
                }
        }
        editor.commit();
        return read(context);
    }
}
