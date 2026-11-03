package com.limelight;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Proxy;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Cartouche's own API on a paired PC (the stream plugin's phone.ts, in Cartouche): the games of
 * its Ma collection with their sheets, covers, play time and save points. HTTPS on PORT. The
 * phone shows its Moonlight certificate, the one the PC paired (the PC answers nothing else),
 * and pins the PC's certificate the first time it sees it; pairing again forgets the pin.
 */
class CartoucheApi {
    static final int PORT = 47975;
    private static final String PINS = "cartouche_api_pins";

    /** No Cartouche answers there: an older Cartouche, the firewall, or another server. */
    static class Unavailable extends IOException {
        Unavailable(String message) {
            super(message);
        }
    }

    private final OkHttpClient client;
    private final HttpUrl base;

    CartoucheApi(Context context, LimelightCryptoProvider crypto, ComputerDetails pc) throws IOException {
        if (pc.activeAddress == null) {
            throw new Unavailable("No address for " + pc.name);
        }
        final SharedPreferences pins = context.getSharedPreferences(PINS, Context.MODE_PRIVATE);
        final String pinKey = "pin-" + pc.uuid;
        X509KeyManager keys = new X509KeyManager() {
            public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) { return "Limelight-RSA"; }
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return null; }
            public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {crypto.getClientCertificate()}; }
            public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
            public PrivateKey getPrivateKey(String alias) { return crypto.getClientPrivateKey(); }
            public String[] getServerAliases(String keyType, Principal[] issuers) { return null; }
        };
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                throw new CertificateException("Not a server");
            }

            public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
                if (certs == null || certs.length != 1) {
                    throw new CertificateException("Not Cartouche's certificate");
                }
                String seen = fingerprint(certs[0]);
                String pinned = pins.getString(pinKey, null);
                if (pinned == null) {
                    pins.edit().putString(pinKey, seen).apply();
                } else if (!pinned.equals(seen)) {
                    throw new CertificateException("Cartouche's certificate changed on " + pc.name + ": pair the phone again");
                }
            }
        };
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(new KeyManager[] {keys}, new TrustManager[] {trust}, new SecureRandom());
            client = new OkHttpClient.Builder()
                    .sslSocketFactory(tls.getSocketFactory(), trust)
                    // The pin is the check: the PC's address is no name its certificate could carry.
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .proxy(Proxy.NO_PROXY)
                    .build();
        } catch (Exception e) {
            throw new IOException(e);
        }
        base = new HttpUrl.Builder().scheme("https").host(pc.activeAddress.address).port(PORT).build();
    }

    /** After a new pairing, the PC's certificate is taken again as it is. */
    static void forget(Context context, String uuid) {
        context.getSharedPreferences(PINS, Context.MODE_PRIVATE).edit().remove("pin-" + uuid).apply();
    }

    private static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new CertificateException(e);
        }
    }

    byte[] bytes(String path) throws IOException {
        HttpUrl url = base.newBuilder().encodedPath(path).build();
        Response response;
        try {
            response = client.newCall(new Request.Builder().url(url).get().build()).execute();
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new Unavailable("Cartouche does not answer on " + url.host());
        }
        try (ResponseBody body = response.body()) {
            if (response.code() == 403) {
                throw new Unavailable("Cartouche does not know this phone");
            }
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Cartouche answered " + response.code());
            }
            return body.bytes();
        }
    }

    JSONObject json(String path) throws IOException, JSONException {
        return new JSONObject(new String(bytes(path), "UTF-8"));
    }
}
