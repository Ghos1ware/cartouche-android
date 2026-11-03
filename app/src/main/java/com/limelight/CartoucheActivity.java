package com.limelight;

import android.app.Activity;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.ConnectingScreen;
import com.limelight.utils.ServerHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Cartouche's home screen: Cartouche's own interface, served by its site, in a WebView, with
 * Moonlight underneath for everything streaming. The page talks to this activity through one
 * message channel, open to the site's origin only (a third-party page or frame never reaches
 * it): the PCs found on the network, pairing, a PC's games with their covers, starting and
 * quitting a game, the stream settings, and browser games, played in the app without a PC.
 *
 * Messages from the page: {"id": n, "method": "...", "args": [...]}; answers: {"id": n, "ok":
 * true, "value": ...} or {"id": n, "ok": false, "error": "..."}; events: {"event": "...",
 * "data": ...}.
 */
public class CartoucheActivity extends Activity {
    static final String ORIGIN = "https://cartouche.borderline.games";
    static final String HOME = ORIGIN + "/?shell=android";

    private WebView web;
    private JavaScriptReplyProxy page;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean polling;
    private boolean inForeground;
    // The PCs polling has reported (the service only reports them one by one).
    private final Map<String, ComputerDetails> computers = new ConcurrentHashMap<>();
    private final ExecutorService work = Executors.newCachedThreadPool();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder = (ComputerManagerService.ComputerManagerBinder) binder;
            work.execute(() -> {
                localBinder.waitForReady();
                managerBinder = localBinder;
                runOnUiThread(CartoucheActivity.this::startPolling);
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inForeground = true;

        // As PcView does: Moonlight's decoder workarounds need the GPU's name, read once per
        // system build through a throwaway GL surface.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();
                    runOnUiThread(() -> completeOnCreate());
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int width, int height) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        } else {
            completeOnCreate();
        }
    }

    private void completeOnCreate() {
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF07060D);
        // A phone's page: no native scrollbars, no overscroll glow.
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " CartoucheAndroid/" + BuildConfig.VERSION_NAME);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (ORIGIN.equals(url.getScheme() + "://" + url.getHost())) {
                    return false;
                }
                // Anywhere else is the browser's: this WebView only ever shows Cartouche.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "CartoucheAndroid", Collections.singleton(ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (!isMainFrame || message.getType() != WebMessageCompat.TYPE_STRING) {
                            return;
                        }
                        page = replyProxy;
                        handle(message.getData());
                    });
        }
        // Android 15 draws apps under the status and navigation bars: the page sits inside them,
        // the bars over Cartouche's dark background, their icons light.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF07060D);
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        getWindow().getDecorView().setBackgroundColor(0xFF07060D);
        setContentView(frame);
        web.loadUrl(HOME);
    }

    // ---- The page's calls ----

    private void handle(String data) {
        final int id;
        final String method;
        final JSONArray args;
        try {
            JSONObject call = new JSONObject(data);
            id = call.getInt("id");
            method = call.getString("method");
            args = call.optJSONArray("args") != null ? call.getJSONArray("args") : new JSONArray();
        } catch (JSONException e) {
            return;
        }
        work.execute(() -> {
            try {
                answer(id, call(method, args));
            } catch (Exception e) {
                fail(id, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private Object call(String method, JSONArray args) throws Exception {
        switch (method) {
            case "hello":
                JSONObject hello = new JSONObject();
                hello.put("version", BuildConfig.VERSION_NAME);
                hello.put("computers", computersJson());
                return hello;
            case "computers":
                return computersJson();
            case "addComputer":
                return addComputer(args.getString(0));
            case "pair":
                return pair(args.getString(0));
            case "apps":
                return apps(args.getString(0));
            case "play":
                play(args.getString(0), args.getInt(1), args.getString(2), args.optBoolean(3, false));
                return JSONObject.NULL;
            case "playWeb":
                playWeb(args.getString(0), args.optString(1, ""));
                return JSONObject.NULL;
            case "quit":
                return connection(computer(args.getString(0))).quitApp();
            case "settings":
                runOnUiThread(() -> startActivity(new Intent(this, StreamSettings.class)));
                return JSONObject.NULL;
            case "moonlight":
                runOnUiThread(() -> startActivity(new Intent(this, PcView.class)));
                return JSONObject.NULL;
            default:
                throw new IllegalArgumentException("Unknown method " + method);
        }
    }

    private ComputerManagerService.ComputerManagerBinder binder() throws IOException {
        if (managerBinder == null) {
            throw new IOException("Moonlight's computer service is not ready yet");
        }
        return managerBinder;
    }

    private ComputerDetails computer(String uuid) throws IOException {
        ComputerDetails details = binder().getComputer(uuid);
        if (details == null) {
            throw new IOException("Unknown PC");
        }
        return details;
    }

    private NvHTTP connection(ComputerDetails details) throws IOException {
        return new NvHTTP(ServerHelper.getCurrentAddressFromComputer(details), details.httpsPort,
                binder().getUniqueId(), details.serverCert, PlatformBinding.getCryptoProvider(this));
    }

    private JSONArray computersJson() throws JSONException {
        JSONArray list = new JSONArray();
        for (ComputerDetails details : computers.values()) {
            list.put(computerJson(details));
        }
        return list;
    }

    private static JSONObject computerJson(ComputerDetails details) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("uuid", details.uuid);
        json.put("name", details.name);
        json.put("state", details.state != null ? details.state.name().toLowerCase() : "unknown");
        json.put("paired", details.pairState == PairState.PAIRED);
        json.put("address", details.activeAddress != null ? details.activeAddress.address : JSONObject.NULL);
        json.put("runningGameId", details.runningGameId);
        return json;
    }

    private boolean addComputer(String address) throws Exception {
        String host = address.trim();
        int port = NvHTTP.DEFAULT_HTTP_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            port = Integer.parseInt(host.substring(colon + 1));
            host = host.substring(0, colon);
        }
        ComputerDetails details = new ComputerDetails();
        details.manualAddress = new ComputerDetails.AddressTuple(host, port);
        return binder().addComputerBlocking(details);
    }

    /** Answers with the PIN to type in Cartouche on the PC; the outcome follows as a "paired" event. */
    private JSONObject pair(String uuid) throws Exception {
        final ComputerDetails details = computer(uuid);
        final NvHTTP http = connection(details);
        final String pin = PairingManager.generatePinString();
        JSONObject started = new JSONObject();
        started.put("pin", pin);
        work.execute(() -> {
            String error = null;
            try {
                runOnUiThread(this::stopPolling);
                binder().waitForPollingStopped();
                if (http.getPairState() != PairState.PAIRED) {
                    PairingManager manager = http.getPairingManager();
                    PairState state = manager.pair(http.getServerInfo(true), pin);
                    if (state == PairState.PAIRED) {
                        binder().getComputer(uuid).serverCert = manager.getPairedCert();
                        binder().invalidateStateForComputer(uuid);
                    } else {
                        error = state.name().toLowerCase();
                    }
                }
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            runOnUiThread(this::startPolling);
            try {
                JSONObject outcome = new JSONObject();
                outcome.put("uuid", uuid);
                outcome.put("ok", error == null);
                outcome.put("error", error != null ? error : JSONObject.NULL);
                event("paired", outcome);
            } catch (JSONException ignored) {
            }
        });
        return started;
    }

    /** The PC's apps (Cartouche Stream publishes Ma collection there), each with its cover as a data URL. */
    private JSONArray apps(String uuid) throws Exception {
        ComputerDetails details = computer(uuid);
        NvHTTP http = connection(details);
        List<NvApp> list = http.getAppList();
        JSONArray apps = new JSONArray();
        for (NvApp app : list) {
            JSONObject json = new JSONObject();
            json.put("id", app.getAppId());
            json.put("name", app.getAppName());
            json.put("hdr", app.isHdrSupported());
            json.put("cover", cover(http, app, ConnectingScreen.coverFile(getCacheDir(), uuid, app.getAppId())));
            apps.put(json);
        }
        return apps;
    }

    /** The cover as a data URL for the page, kept on disk for the connection screen too. */
    private static Object cover(NvHTTP http, NvApp app, File keep) {
        try (InputStream in = http.getBoxArt(app)) {
            if (in == null) {
                return JSONObject.NULL;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, read);
            }
            byte[] image = bytes.toByteArray();
            try {
                keep.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(keep)) {
                    out.write(image);
                }
            } catch (IOException ignored) {
                // The connection screen shows the cartridge instead.
            }
            String type = image.length > 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 ? "image/jpeg" : "image/png";
            return "data:" + type + ";base64," + Base64.encodeToString(image, Base64.NO_WRAP);
        } catch (IOException e) {
            return JSONObject.NULL;
        }
    }

    /** Moonlight's own shortcut path: it handles an offline PC, another game running, and so on. */
    private void play(String uuid, int appId, String appName, boolean hdr) throws IOException {
        ComputerDetails details = computer(uuid);
        Intent intent = new Intent(this, ShortcutTrampoline.class);
        intent.putExtra(AppView.UUID_EXTRA, uuid);
        intent.putExtra(AppView.NAME_EXTRA, details.name);
        intent.putExtra(Game.EXTRA_APP_ID, String.valueOf(appId));
        intent.putExtra(Game.EXTRA_APP_NAME, appName);
        intent.putExtra(Game.EXTRA_APP_HDR, hdr);
        runOnUiThread(() -> startActivity(intent));
    }

    /** A browser game, full screen in the app: no PC needed. */
    private void playWeb(String url, String title) {
        Uri target = Uri.parse(url);
        if (!"https".equals(target.getScheme()) && !"http".equals(target.getScheme())) {
            throw new IllegalArgumentException("Not a web game: " + url);
        }
        Intent intent = new Intent(this, WebGameActivity.class);
        intent.putExtra(WebGameActivity.EXTRA_URL, url);
        intent.putExtra(WebGameActivity.EXTRA_TITLE, title);
        runOnUiThread(() -> startActivity(intent));
    }

    // ---- Answers and events to the page ----

    private void answer(int id, Object value) {
        try {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("ok", true);
            json.put("value", value);
            send(json);
        } catch (JSONException ignored) {
        }
    }

    private void fail(int id, String error) {
        try {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("ok", false);
            json.put("error", error);
            send(json);
        } catch (JSONException ignored) {
        }
    }

    private void event(String name, Object data) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("event", name);
        json.put("data", data);
        send(json);
    }

    private void send(JSONObject json) {
        final String text = json.toString();
        runOnUiThread(() -> {
            if (page != null) {
                page.postMessage(text);
            }
        });
    }

    // ---- PCs on the network, while the page is shown ----

    private void startPolling() {
        if (managerBinder == null || polling || !inForeground) {
            return;
        }
        polling = true;
        managerBinder.startPolling(details -> {
            if (details.uuid != null) {
                computers.put(details.uuid, details);
            }
            try {
                event("computer", computerJson(details));
            } catch (JSONException ignored) {
            }
        });
    }

    private void stopPolling() {
        if (managerBinder != null && polling) {
            managerBinder.stopPolling();
            polling = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        startPolling();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
        stopPolling();
        if (web != null) {
            web.onPause();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Cartouche's "back" is Escape, as on the PC; the page closes what is open.
        if (web != null) {
            web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'AndroidBack', bubbles: true }))", null);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
        if (web != null) {
            web.destroy();
        }
        work.shutdownNow();
    }
}
