package com.limelight;

import android.app.Activity;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
import com.limelight.preferences.CartoucheStreamSettings;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.StreamSettings;
import com.limelight.ui.ConnectingScreen;
import com.limelight.ui.LoadingScreen;
import com.limelight.utils.ServerHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * quitting a game, the stream settings, browser games, played in the app without a PC, and the
 * PC's Ma collection from Cartouche itself (CartoucheApi: sheets, play time, save points).
 *
 * Messages from the page: {"id": n, "method": "...", "args": [...]}; answers: {"id": n, "ok":
 * true, "value": ...} or {"id": n, "ok": false, "error": "..."}; events: {"event": "...",
 * "data": ...}.
 */
public class CartoucheActivity extends Activity {
    static final String ORIGIN = "https://cartouche.gg";
    static final String HOME = ORIGIN + "/?shell=android";
    // Covers reach the page as addresses under the site that this WebView answers itself, one
    // image at a time: as data URLs in the answers, a PC's collection (hundreds of covers) ran
    // the app out of memory.
    static final String COVERS = "/__android/covers/";

    private WebView web;
    // Shown until the page is there; says so when the site does not answer (12 s: slow).
    private LoadingScreen loading;
    private boolean pageFailed;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private final Runnable slow = () -> loading.failed(true);
    private JavaScriptReplyProxy page;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean polling;
    private boolean inForeground;
    // The PCs polling has reported (the service only reports them one by one).
    private final Map<String, ComputerDetails> computers = new ConcurrentHashMap<>();
    private final ExecutorService work = Executors.newCachedThreadPool();
    // The page's <input type="file"> (the Studio's photos and videos): the picker the WebView waits on.
    private static final int PICK_FILES = 0xCA27;
    private ValueCallback<Uri[]> picking;
    private Uri photoTaken;

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
                // Paying (Pro, Max, a recharge, the subscription's portal) stays in the app: Stripe's
                // pages load here and their way back (cartouche.gg/offres?merci=…) lands in Cartouche,
                // not in the phone's browser. The page's bridge stays limited to ORIGIN.
                if (isStripe(url)) {
                    return false;
                }
                // Linking GitHub to the account (« Envoyer sur GitHub »): GitHub's sign-in, its authorization
                // and Cartouche Studio's install load here, and their way back (cartouche.gg) lands in Cartouche.
                if (isGithubLink(url)) {
                    return false;
                }
                // A bank's app asked by its 3-D Secure page (intent://…): open that app.
                if ("intent".equals(url.getScheme())) {
                    try {
                        Intent intent = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME);
                        intent.addCategory(Intent.CATEGORY_BROWSABLE);
                        intent.setComponent(null);
                        intent.setSelector(null);
                        startActivity(intent);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                // Anywhere else is the browser's: this WebView only ever shows Cartouche.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String path = url.getPath();
                if (!ORIGIN.equals(url.getScheme() + "://" + url.getHost()) || path == null || !path.startsWith(COVERS)) {
                    return null;
                }
                return coverResponse(path.substring(COVERS.length()));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageFailed = false;
                loading.loading();
                clock.removeCallbacks(slow);
                clock.postDelayed(slow, 12_000);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                clock.removeCallbacks(slow);
                if (!pageFailed) {
                    loading.done();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    fail();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                    fail();
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return chooseFiles(callback, params);
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
        loading = new LoadingScreen(this, () -> {
            loading.loading();
            web.loadUrl(HOME);
        });
        frame.addView(loading.view(), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        loading.loading();
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

    // ---- The page's file inputs ----

    /**
     * Android's own chooser for an <input type="file">: the gallery and the files (several at once
     * when the input says multiple), and the camera for a new photo, saved in the phone's gallery
     * (no permission needed from Android 10 on; before, the gallery only).
     */
    private boolean chooseFiles(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (picking != null) {
            picking.onReceiveValue(null);
        }
        forgetPhoto();
        picking = callback;

        ArrayList<String> types = new ArrayList<>();
        for (String accept : params.getAcceptTypes()) {
            for (String type : accept.split(",")) {
                type = type.trim();
                if (type.contains("/")) {
                    types.add(type);
                }
            }
        }
        Intent files = new Intent(Intent.ACTION_GET_CONTENT);
        files.addCategory(Intent.CATEGORY_OPENABLE);
        files.setType(types.size() == 1 ? types.get(0) : "*/*");
        if (types.size() > 1) {
            files.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        }
        files.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);

        boolean images = types.isEmpty();
        for (String type : types) {
            images |= type.startsWith("image/") || type.equals("*/*");
        }
        Intent chooser = Intent.createChooser(files, null);
        if (images && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "Cartouche_" + System.currentTimeMillis() + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cartouche");
                photoTaken = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (photoTaken != null) {
                    Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    camera.putExtra(MediaStore.EXTRA_OUTPUT, photoTaken);
                    camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                }
            } catch (Exception e) {
                forgetPhoto();
            }
        }
        try {
            startActivityForResult(chooser, PICK_FILES);
            return true;
        } catch (ActivityNotFoundException e) {
            forgetPhoto();
            picking = null;
            return false;
        }
    }

    /** The camera's empty photo, when it was not taken. */
    private void forgetPhoto() {
        if (photoTaken != null) {
            try {
                getContentResolver().delete(photoTaken, null, null);
            } catch (Exception ignored) {
            }
            photoTaken = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILES) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> callback = picking;
        picking = null;
        Uri[] chosen = null;
        if (resultCode == RESULT_OK) {
            ClipData clip = data != null ? data.getClipData() : null;
            if (clip != null && clip.getItemCount() > 0) {
                chosen = new Uri[clip.getItemCount()];
                for (int i = 0; i < chosen.length; i++) {
                    chosen[i] = clip.getItemAt(i).getUri();
                }
            } else if (data != null && data.getData() != null) {
                chosen = new Uri[]{data.getData()};
            } else if (photoTaken != null) {
                // The camera answers with nothing: its photo is where it was asked to write it.
                chosen = new Uri[]{photoTaken};
                photoTaken = null;
            }
        }
        forgetPhoto();
        if (callback != null) {
            callback.onReceiveValue(chosen);
        }
    }

    /** The site did not answer: the page behind is the WebView's error, never shown. */
    private void fail() {
        pageFailed = true;
        clock.removeCallbacks(slow);
        loading.failed(false);
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
                // What this app does beyond the first calls: the page's file inputs (0.1.17).
                hello.put("features", new JSONArray().put("files"));
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
            case "openBrowser":
                openBrowser(args.getString(0));
                return JSONObject.NULL;
            case "streamSettings":
                return CartoucheStreamSettings.read(this);
            case "setStreamSetting":
                return CartoucheStreamSettings.set(this, args.getString(0), args.getString(1));
            case "settings":
                runOnUiThread(() -> startActivity(new Intent(this, StreamSettings.class)));
                return JSONObject.NULL;
            case "moonlight":
                runOnUiThread(() -> startActivity(new Intent(this, PcView.class)));
                return JSONObject.NULL;
            case "pcLibrary":
                return pcLibrary(args.getString(0));
            case "pcGame":
                return api(args.getString(0)).json("/v1/games/" + gameId(args.getString(1)));
            case "pcCover":
                return pcCover(args.getString(0), gameId(args.getString(1)), args.optString(2, ""));
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
                        CartoucheApi.forget(this, uuid);
                        apis.clear();
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

    /** The PC's apps (Cartouche Stream publishes Ma collection there), each with its cover's address. */
    private JSONArray apps(String uuid) throws Exception {
        ComputerDetails details = computer(uuid);
        List<NvApp> list = connection(details).getAppList();
        JSONArray apps = new JSONArray();
        for (NvApp app : list) {
            JSONObject json = new JSONObject();
            json.put("id", app.getAppId());
            json.put("name", app.getAppName());
            json.put("hdr", app.isHdrSupported());
            json.put("cover", ORIGIN + COVERS + "app/" + uuid + "/" + app.getAppId());
            apps.put(json);
        }
        return apps;
    }

    // ---- Covers, answered to the page's <img> one by one (COVERS) ----

    // The app covers read from their PC since the app started; the others are read once, then
    // shown from the phone's copy.
    private final Set<String> coversRead = ConcurrentHashMap.newKeySet();

    /** "app/<uuid>/<appId>": a stream server's box art; "pc/<file>": a cover pcCover kept. */
    private WebResourceResponse coverResponse(String name) {
        File file = null;
        try {
            String[] parts = name.split("/");
            if (parts.length == 3 && parts[0].equals("app") && parts[1].matches("[0-9A-Za-z-]+") && parts[2].matches("[0-9]+")) {
                file = appCover(parts[1], Integer.parseInt(parts[2]));
            } else if (parts.length == 2 && parts[0].equals("pc") && parts[1].matches("[0-9A-Za-z-]+\\.png")) {
                file = new File(new File(getCacheDir(), "pc-covers"), parts[1]);
            }
        } catch (Exception ignored) {
            // No cover: the page shows the name.
        }
        try {
            if (file != null && file.isFile()) {
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-cache");
                return new WebResourceResponse(imageType(file), null, 200, "OK", headers, new FileInputStream(file));
            }
        } catch (IOException ignored) {
        }
        return new WebResourceResponse("text/plain", null, 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
    }

    /** The box art, kept on disk for the connection screen too, read again from the PC once per run. */
    private File appCover(String uuid, int appId) throws IOException {
        File keep = ConnectingScreen.coverFile(getCacheDir(), uuid, appId);
        String key = uuid + "-" + appId;
        if (keep.isFile() && coversRead.contains(key)) {
            return keep;
        }
        try (InputStream in = connection(computer(uuid)).getBoxArt(new NvApp("", appId, false))) {
            if (in != null) {
                byte[] image = readAll(in);
                keep.getParentFile().mkdirs();
                File part = new File(keep.getPath() + ".part");
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(image);
                }
                if (!part.renameTo(keep)) {
                    part.delete();
                }
                coversRead.add(key);
            }
        } catch (IOException e) {
            // The PC did not answer: its last cover, if the phone has one.
            if (!keep.isFile()) {
                throw e;
            }
        }
        return keep;
    }

    private static String imageType(File file) throws IOException {
        byte[] head = new byte[2];
        try (InputStream in = new FileInputStream(file)) {
            return in.read(head) == 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 ? "image/jpeg" : "image/png";
        }
    }

    // ---- Cartouche's own API on the PC (CartoucheApi): Ma collection, sheets, covers ----

    private final Map<String, CartoucheApi> apis = new ConcurrentHashMap<>();

    private CartoucheApi api(String uuid) throws IOException {
        ComputerDetails details = computer(uuid);
        String key = uuid + "@" + (details.activeAddress != null ? details.activeAddress.address : "");
        CartoucheApi api = apis.get(key);
        if (api == null) {
            api = new CartoucheApi(this, PlatformBinding.getCryptoProvider(this), details);
            apis.put(key, api);
        }
        return api;
    }

    private static String gameId(String id) {
        if (!id.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Not a game id: " + id);
        }
        return id;
    }

    /** The PC's Ma collection, or null when no Cartouche answers there (the page shows Moonlight's list). */
    private Object pcLibrary(String uuid) throws Exception {
        try {
            CartoucheApi api = api(uuid);
            JSONObject library = api.json("/v1/hello");
            library.put("games", api.json("/v1/games").getJSONArray("games"));
            return library;
        } catch (CartoucheApi.Unavailable e) {
            return JSONObject.NULL;
        }
    }

    /** A game's cover from the PC, kept on disk under its fingerprint (a new cover, a new file); answers its address. */
    private String pcCover(String uuid, String gameId, String fingerprint) throws IOException {
        File dir = new File(getCacheDir(), "pc-covers");
        String name = uuid + "-" + gameId + "-" + fingerprint.replaceAll("[^a-z0-9]", "") + ".png";
        File file = new File(dir, name);
        if (!file.isFile()) {
            byte[] image = api(uuid).bytes("/v1/games/" + gameId + "/cover");
            dir.mkdirs();
            File part = new File(dir, name + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(image);
            }
            if (!part.renameTo(file)) {
                part.delete();
                throw new IOException("The cover could not be kept");
            }
        }
        return ORIGIN + COVERS + "pc/" + name;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
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

    /** A page of the site in the phone's browser (signing in: Steam and Google refuse a WebView). Ours only. */
    private void openBrowser(String url) {
        if (!url.startsWith(ORIGIN + "/")) {
            throw new IllegalArgumentException("Not a page of Cartouche: " + url);
        }
        runOnUiThread(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ignored) {
            }
        });
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

    /** Stripe's checkout and customer portal, and the pages they send to (3-D Secure, Link). */
    /** GitHub's pages of linking an account: sign-in, two-factor, the authorization, the App's install. */
    static boolean isGithubLink(Uri url) {
        String path = url.getPath();
        if (!"https".equals(url.getScheme()) || !"github.com".equals(url.getHost()) || path == null) return false;
        return path.equals("/login") || path.startsWith("/login/") || path.equals("/session") || path.startsWith("/sessions/")
                || path.startsWith("/apps/cartouche-studio") || path.startsWith("/installations/") || path.startsWith("/settings/installations");
    }

    static boolean isStripe(Uri url) {
        String host = url.getHost();
        return "https".equals(url.getScheme()) && host != null && (host.equals("stripe.com") || host.endsWith(".stripe.com") || host.endsWith(".stripe.network") || host.equals("link.com") || host.endsWith(".link.com"));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // On Stripe's page (paying in the app), back goes back to Cartouche.
        String current = web != null ? web.getUrl() : null;
        if (current != null && (isStripe(Uri.parse(current)) || isGithubLink(Uri.parse(current))) && web.canGoBack()) {
            web.goBack();
            return;
        }
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
