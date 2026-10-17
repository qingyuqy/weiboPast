package com.qingyu.weibopast.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.qingyu.weibopast.R;
import com.qingyu.weibopast.constants.APPConstants;
import com.sina.weibo.sdk.auth.AccessTokenHelper;
import com.sina.weibo.sdk.auth.Oauth2AccessToken;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";
    private Intent mainIntent;
    private WebView webView;
    private ScrollView loginForm;
    private ProgressBar progressBar;
    private OkHttpClient httpClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        webView = findViewById(R.id.webview);
        loginForm = findViewById(R.id.login_form);
        progressBar = findViewById(R.id.login_progress);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        mainIntent = new Intent(this, MainActivity.class);

        android.content.SharedPreferences prefs = getSharedPreferences("weibo_token", MODE_PRIVATE); String savedToken = prefs.getString("access_token", null); long expiresTime = prefs.getLong("expires_time", 0); boolean hasValidToken = savedToken != null && !savedToken.isEmpty() && (expiresTime == 0 || System.currentTimeMillis() < expiresTime);
        if (hasValidToken) {
            startActivity(mainIntent);
            finish();
            return;
        }

        Button btSignIn = findViewById(R.id.btSignIn);
        btSignIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startWebAuth();
            }
        });
    }

    private void exchangeCodeForToken(final String code) {
        progressBar.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = "https://api.weibo.com/oauth2/access_token"
                            + "?client_id=" + APPConstants.APP_KEY
                            + "&client_secret=" + "c780374d7097f7c8c1f95eb9c90dd576"
                            + "&grant_type=authorization_code"
                            + "&code=" + code
                            + "&redirect_uri=" + Uri.encode(APPConstants.REDIRECT_URL);

                    Request request = new Request.Builder().url(url).post(okhttp3.RequestBody.create("", null)).build();
                    Response response = httpClient.newCall(request).execute();
                    String body = response.body() != null ? response.body().string() : "";

                    Log.d(TAG, "Token response: " + body);

                    // Parse JSON response
                    org.json.JSONObject json = new org.json.JSONObject(body);
                    if (json.has("access_token")) {
                        final String tokenStr = json.getString("access_token");
                        final String uidStr = json.optString("uid", "");
                        long expiresIn = json.optLong("expires_in", 86400);

                        Oauth2AccessToken token = new Oauth2AccessToken();
                        token.setAccessToken(tokenStr);
                        token.setUid(uidStr);
                        token.setExpiresTime(System.currentTimeMillis() + expiresIn * 1000);
                        android.content.SharedPreferences prefs = getSharedPreferences("weibo_token", MODE_PRIVATE);
                        prefs.edit().putString("access_token", tokenStr).putString("uid", uidStr).putLong("expires_time", token.getExpiresTime()).commit();

                        // Save m.weibo.cn cookies for mobile API fallback
                        try {
                            String mwCookie = android.webkit.CookieManager.getInstance().getCookie("https://m.weibo.cn");
                            if (mwCookie != null && !mwCookie.isEmpty()) {
                                prefs.edit().putString("mweibo_cookie", mwCookie).commit();
                                Log.i(TAG, "Saved m.weibo.cn cookies");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to save m.weibo.cn cookies", e);
                        }

                        Log.i(TAG, "Auth success! token=" + tokenStr);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LoginActivity.this, "Auth success!", Toast.LENGTH_SHORT).show();
                                startActivity(mainIntent);
                                finish();
                            }
                        });
                    } else {
                        final String err = json.optString("error", body);
                        Log.e(TAG, "Token exchange failed: " + err);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LoginActivity.this, "Token error: " + err, Toast.LENGTH_LONG).show();
                                resetToLoginForm();
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Token exchange exception", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LoginActivity.this, "Network error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            resetToLoginForm();
                        }
                    });
                }
            }
        }).start();
    }

    private boolean handleRedirectUrl(String url) {
        if (url == null) return false;
        Log.d(TAG, "checkRedirect: " + url);

        if (!url.startsWith(APPConstants.REDIRECT_URL)) {
            return false;
        }

        if (url.contains("error=") || url.contains("error%3A")
                || url.contains("error_code=") || url.contains("error_code%3A")) {
            final String errMsg = "Auth error: " + url;
            Log.e(TAG, errMsg);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(LoginActivity.this, errMsg, Toast.LENGTH_LONG).show();
                    resetToLoginForm();
                }
            });
            return true;
        }

        // Handle authorization code flow: redirect_uri?code=xxx
        if (url.contains("code=")) {
            try {
                String query = url.contains("?") ? url.substring(url.indexOf("?") + 1) : "";
                if (url.contains("#")) query = url.substring(url.indexOf("?") + 1, url.indexOf("#"));
                String[] params = query.split("&");
                String code = "";
                for (String param : params) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2 && "code".equals(kv[0])) {
                        code = kv[1];
                        break;
                    }
                }
                if (!code.isEmpty()) {
                    Log.i(TAG, "Got authorization code, exchanging for token...");
                    webView.setVisibility(View.GONE);
                    exchangeCodeForToken(code);
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse code", e);
            }
        }

        // Handle implicit grant: redirect_uri#access_token=xxx
        if (url.contains("access_token")) {
            try {
                String fragment = url.substring(url.indexOf("#") + 1);
                String decodedFragment = Uri.decode(fragment);
                Log.d(TAG, "Fragment: " + decodedFragment);
                String[] params = decodedFragment.split("&");
                String tokenStr = "";
                String uidStr = "";
                long expiresIn = 0;
                for (String param : params) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        String key = kv[0].replace(":", "").trim();
                        String val = kv[1];
                        switch (key) {
                            case "access_token": tokenStr = val; break;
                            case "uid": uidStr = val; break;
                            case "expires_in": try { expiresIn = Long.parseLong(val); } catch (Exception ignored) {} break;
                        }
                    }
                }
                if (!tokenStr.isEmpty()) {
                    Oauth2AccessToken token = new Oauth2AccessToken();
                    token.setAccessToken(tokenStr);
                    token.setUid(uidStr);
                    if (expiresIn > 0) token.setExpiresTime(System.currentTimeMillis() + expiresIn * 1000);
                    android.content.SharedPreferences prefs = getSharedPreferences("weibo_token", MODE_PRIVATE);
                    prefs.edit().putString("access_token", tokenStr).putString("uid", uidStr).putLong("expires_time", token.getExpiresTime()).commit();

                    // Save m.weibo.cn cookies for mobile API fallback
                    try {
                        String mwCookie = android.webkit.CookieManager.getInstance().getCookie("https://m.weibo.cn");
                        if (mwCookie != null && !mwCookie.isEmpty()) {
                            prefs.edit().putString("mweibo_cookie", mwCookie).commit();
                            Log.i(TAG, "Saved m.weibo.cn cookies");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to save m.weibo.cn cookies", e);
                    }
                    Log.i(TAG, "Auth success! token=" + tokenStr);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LoginActivity.this, "Auth success!", Toast.LENGTH_SHORT).show();
                            startActivity(mainIntent);
                            finish();
                        }
                    });
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse token", e);
            }
        }
        return false;
    }

    private void startWebAuth() {
        loginForm.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        // Use authorization code flow (response_type=code) which has broader platform support
        // We'll exchange the code for token ourselves
        String authUrl = "https://api.weibo.com/oauth2/authorize"
                + "?client_id=" + APPConstants.APP_KEY
                + "&response_type=code"
                + "&redirect_uri=" + Uri.encode(APPConstants.REDIRECT_URL)
                + "&scope=" + Uri.encode("statuses_to_me_read,friendships_groups_read,email,follow_app_official_microblog") + "&display=mobile";

        webView.setVisibility(View.VISIBLE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.clearCache(true);
        webView.clearHistory();

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onConsoleMessage(String message, int lineNumber, String sourceID) {
                Log.d(TAG, "Console: [" + sourceID + ":" + lineNumber + "] " + message);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "onPageStarted: " + url);
                progressBar.setVisibility(View.VISIBLE);
                if (handleRedirectUrl(url)) {
                    webView.stopLoading();
                    webView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                progressBar.setVisibility(View.GONE);
                if (handleRedirectUrl(url)) {
                    webView.stopLoading();
                    webView.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "shouldOverrideUrlLoading: " + (url != null ? url.substring(0, Math.min(url.length(), 200)) : "null"));
                if (handleRedirectUrl(url)) return true;
                if (url.startsWith("sinaweibo://browser")) {
                    try {
                        Uri uri = Uri.parse(url);
                        String realUrl = uri.getQueryParameter("url");
                        if (realUrl != null && !realUrl.isEmpty()) {
                            view.loadUrl(Uri.decode(realUrl));
                            return true;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse sinaweibo URL", e);
                    }
                }
                if (url.startsWith("sinaweibo://browser/close")) return true;
                return !(url.startsWith("http://") || url.startsWith("https://"));
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error: " + errorCode + " - " + description + " url=" + failingUrl);
                if (failingUrl != null) handleRedirectUrl(failingUrl);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                Log.d(TAG, "doUpdateVisitedHistory: " + url);
                super.doUpdateVisitedHistory(view, url, isReload);
                if (handleRedirectUrl(url)) {
                    webView.stopLoading();
                    webView.setVisibility(View.GONE);
                }
            }
        });

        webView.loadUrl(authUrl);
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            resetToLoginForm();
        }
    }

    private void resetToLoginForm() {
        webView.setVisibility(View.GONE);
        webView.stopLoading();
        progressBar.setVisibility(View.GONE);
        loginForm.setVisibility(View.VISIBLE);
    }
}