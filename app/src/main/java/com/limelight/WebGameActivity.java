package com.limelight;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A browser game from Cartouche's catalog, full screen in the app: no PC needed. The game is a
 * page of its own site, so this WebView has no channel to Cartouche (CartoucheActivity's stays
 * on Cartouche's origin). Its saves live in the app's web storage and cookies, kept between
 * sessions. Pages elsewhere than the game's site open in the browser.
 *
 * Google refuses its sign-in inside an app's WebView. The game's page gets the shell its own
 * Android app gives it (Voltane's window.voltaneShell, its page only): its sign-in opens in the
 * phone's browser, where the player's Google session is, and the one-time code comes back
 * through a loopback server of this screen's (127.0.0.1, a free port, /voltane-auth: the
 * address Voltane's server accepts), handed to the page.
 */
public class WebGameActivity extends Activity {
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private WebView web;
    private String site;
    /** The game's own origin: the only page the shell is given to. */
    private String origin;
    private ServerSocket authServer;
    private String authAddress;
    private final ExecutorService work = Executors.newCachedThreadPool();

    /** Voltane's shell (src/account/shell.ts in Voltane), over the channel Android opens to the game's origin. */
    private static final String SHELL_SCRIPT =
            "(function () { "
            + "  var channel = window.CartoucheGameShell; "
            + "  if (!channel || window.voltaneShell) return; "
            + "  var next = 1, pending = {}; "
            + "  channel.addEventListener('message', function (event) { "
            + "    var reply = JSON.parse(event.data), done = pending[reply.id]; "
            + "    if (done) { delete pending[reply.id]; done(reply.value); } "
            + "  }); "
            + "  function call(method, args) { "
            + "    return new Promise(function (resolve) { var id = next++; pending[id] = resolve; channel.postMessage(JSON.stringify({ id: id, method: method, args: args })); }); "
            + "  } "
            + "  window.voltaneShell = { platform: 'android', openExternal: function (url) { return call('openExternal', [url]); }, authReturn: function () { return call('authReturn', []); } }; "
            + "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri url = Uri.parse(getIntent().getStringExtra(EXTRA_URL));
        if (url.getHost() == null || !("https".equals(url.getScheme()) || "http".equals(url.getScheme()))) {
            finish();
            return;
        }
        site = siteOf(url.getHost());
        origin = url.getScheme() + "://" + url.getHost() + (url.getPort() != -1 ? ":" + url.getPort() : "");
        setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (target.getHost() != null && site.equals(siteOf(target.getHost()))) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, target));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> rules = Collections.singleton(origin);
            WebViewCompat.addWebMessageListener(web, "CartoucheGameShell", rules,
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        if (isMainFrame && message.getType() == WebMessageCompat.TYPE_STRING) {
                            shell(message.getData(), replyProxy);
                        }
                    });
            WebViewCompat.addDocumentStartJavaScript(web, SHELL_SCRIPT, rules);
        }
        setContentView(web);
        immersive();
        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(url.toString());
        }
    }

    // ---- The game's shell: Google's sign-in in the phone's browser ----

    private void shell(String data, JavaScriptReplyProxy page) {
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
            Object value = JSONObject.NULL;
            try {
                if ("openExternal".equals(method)) {
                    Uri target = Uri.parse(args.getString(0));
                    // Only the game's own sign-in, on its own address: nothing else leaves through here.
                    String targetOrigin = target.getScheme() + "://" + target.getHost() + (target.getPort() != -1 ? ":" + target.getPort() : "");
                    if (origin.equals(targetOrigin) && "/api/auth/google".equals(target.getPath())) {
                        runOnUiThread(() -> {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, target));
                            } catch (ActivityNotFoundException ignored) {
                            }
                        });
                    }
                } else if ("authReturn".equals(method)) {
                    value = authReturn();
                }
            } catch (Exception ignored) {
            }
            try {
                JSONObject reply = new JSONObject();
                reply.put("id", id);
                reply.put("value", value);
                final String text = reply.toString();
                runOnUiThread(() -> page.postMessage(text));
            } catch (JSONException ignored) {
            }
        });
    }

    /** The loopback address the phone's browser hands the sign-in back to, started once. */
    private synchronized String authReturn() throws IOException {
        if (authAddress != null) {
            return authAddress;
        }
        authServer = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        authAddress = "http://127.0.0.1:" + authServer.getLocalPort() + "/voltane-auth";
        final ServerSocket server = authServer;
        work.execute(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    answer(socket);
                } catch (IOException ignored) {
                }
            }
        });
        return authAddress;
    }

    private void answer(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        boolean english = false;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("accept-language:") && line.substring(16).trim().toLowerCase().startsWith("en")) {
                english = true;
            }
        }
        String[] parts = request != null ? request.split(" ") : new String[0];
        Uri asked = parts.length > 1 ? Uri.parse("http://127.0.0.1" + parts[1]) : null;
        OutputStream out = socket.getOutputStream();
        if (asked == null || !"/voltane-auth".equals(asked.getPath())) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }
        // Back to the game: Cartouche's task, its game screen on top.
        String back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=" + getPackageName() + ";end";
        String page = "<!doctype html><html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Cartouche</title>"
                + "<body style=\"margin:0;min-height:100vh;display:grid;place-items:center;text-align:center;background:#07060d;color:#f6f3ff;font:600 20px system-ui,sans-serif\">"
                + "<div><p>" + (english ? "Signed in." : "C'est fait.") + "</p>"
                + "<p><a href=\"" + back + "\" style=\"display:inline-block;padding:14px 26px;border-radius:999px;background:#f6f3ff;color:#0c0a16;text-decoration:none\">"
                + (english ? "Back to the game" : "Retour au jeu") + "</a></p></div>"
                + "<script>location.href = " + JSONObject.quote(back) + ";</script></body></html>";
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
        String code = asked.getQueryParameter("code");
        if (code != null) {
            handBack(code);
        }
    }

    /** The code to the game's page: kept where Voltane looks on load, and sent as its event. */
    private void handBack(String code) {
        final String quoted = JSONObject.quote(code);
        runOnUiThread(() -> {
            if (web == null) {
                return;
            }
            // Only ever to the game's own page.
            Uri current = Uri.parse(web.getUrl() != null ? web.getUrl() : "");
            String currentOrigin = current.getScheme() + "://" + current.getHost() + (current.getPort() != -1 ? ":" + current.getPort() : "");
            if (!origin.equals(currentOrigin)) {
                return;
            }
            web.evaluateJavascript("try { localStorage.setItem('voltane.shell.auth', " + quoted + "); } catch (e) {} "
                    + "window.dispatchEvent(new CustomEvent('voltane-auth', { detail: { code: " + quoted + " } }));", null);
        });
    }

    /** "play.example.com" → "example.com": a game's pages and its subdomains stay in the game. */
    private static String siteOf(String host) {
        String[] labels = host.split("\\.");
        return labels.length <= 2 ? host : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            immersive();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
        }
        // Write the game's cookies out now: a closed app keeps its saves.
        CookieManager.getInstance().flush();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (authServer != null) {
            try {
                authServer.close();
            } catch (IOException ignored) {
            }
        }
        work.shutdownNow();
        if (web != null) {
            web.destroy();
        }
    }
}
