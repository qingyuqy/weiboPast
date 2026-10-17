package com.qingyu.weibopast.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;

import com.qingyu.weibopast.beans.User;
import com.qingyu.weibopast.constants.APIConstants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WeiboApiClient {

    private static final String TAG = "WeiboApiClient";
    private static final String PREFS_NAME = "weibo_token";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_UID = "uid";
    private static final String KEY_EXPIRES = "expires_time";
    private static final String KEY_MWEIBO_COOKIE = "mweibo_cookie";

    private OkHttpClient httpClient;
    private String accessToken;
    private String uid;
    private Handler mainHandler;
    private Context appContext;

    // Cached user to avoid repeated API calls
    private User cachedUser;
    private long cachedUserTime = 0;
    private static final long USER_CACHE_MS = 300000; // 5 min

    public WeiboApiClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.accessToken = prefs.getString(KEY_TOKEN, null);
        this.uid = prefs.getString(KEY_UID, null);
        long expiresTime = prefs.getLong(KEY_EXPIRES, 0);

        if (accessToken != null && expiresTime > 0 && System.currentTimeMillis() > expiresTime) {
            Log.d(TAG, "Token expired");
            this.accessToken = null;
            this.uid = null;
        }

        // Restore m.weibo.cn cookies from saved preferences
        try {
            String savedCookie = prefs.getString(KEY_MWEIBO_COOKIE, null);
            if (savedCookie != null && !savedCookie.isEmpty()) {
                String[] cookies = savedCookie.split(";");
                for (String c : cookies) {
                    String trimmed = c.trim();
                    if (!trimmed.isEmpty()) {
                        CookieManager.getInstance().setCookie("https://m.weibo.cn", trimmed);
                        CookieManager.getInstance().setCookie("https://.weibo.cn", trimmed);
                    }
                }
                Log.d(TAG, "Restored " + cookies.length + " m.weibo.cn cookies");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore cookies", e);
        }

        Log.d(TAG, "init token=" + (accessToken != null ? accessToken.substring(0, Math.min(10, accessToken.length())) + "..." : "null"));
    }

    public boolean isAuthenticated() {
        return accessToken != null && !accessToken.isEmpty();
    }

    public String getAccessToken() { return accessToken; }
    public String getUid() { return uid; }

    // ==================== User Info ====================

    public void getUserInfo(final ApiCallback<User> callback) {
        if (!isAuthenticated()) { postFailure(callback, "Not authenticated"); return; }

        // Return cached user if fresh
        if (cachedUser != null && (System.currentTimeMillis() - cachedUserTime) < USER_CACHE_MS) {
            postSuccess(callback, cachedUser);
            return;
        }

        String url = APIConstants.HTTP_SERVER_URL + APIConstants.API_USER_ME
                + "?access_token=" + accessToken + "&uid=" + uid;

        executeRequest(url, new ApiCallback<String>() {
            @Override
            public void onSuccess(String response) {
                try {
                    User user = WeiboParser.parseUser(response);
                    if (user != null) {
                        cachedUser = user;
                        cachedUserTime = System.currentTimeMillis();
                        postSuccess(callback, user);
                    } else if (cachedUser != null) {
                        postSuccess(callback, cachedUser);
                    } else {
                        postFailure(callback, "Failed to parse user info");
                    }
                } catch (Exception e) {
                    if (cachedUser != null) {
                        postSuccess(callback, cachedUser);
                    } else {
                        postFailure(callback, "Parse error: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                if (cachedUser != null) {
                    postSuccess(callback, cachedUser);
                } else {
                    postFailure(callback, errorMsg);
                }
            }
        });
    }

    // ==================== Timeline (mobile API only) ====================

    public void getUserTimeline(int page, int count, final ApiCallback<List<User.Status>> callback) {
        if (!isAuthenticated()) { postFailure(callback, "Not authenticated"); return; }
        // Official API requires "statuses_to_me_read" scope which we don''t have.
        // Go directly to mobile API.
        fetchMobileUserTimeline(page, count, callback);
    }

    // ==================== Mobile API ====================

    public void fetchMobileUserTimeline(final int page, final int count, final ApiCallback<List<User.Status>> callback) {
        try {
            // Get cookies: first from CookieManager, then from saved prefs
            String cookieStr = CookieManager.getInstance().getCookie("https://m.weibo.cn");
            if (cookieStr == null || cookieStr.isEmpty()) {
                cookieStr = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_MWEIBO_COOKIE, null);
                if (cookieStr != null && !cookieStr.isEmpty()) {
                    // Restore cookies to CookieManager
                    String[] cookies = cookieStr.split(";");
                    for (String c : cookies) {
                        String trimmed = c.trim();
                        if (!trimmed.isEmpty()) {
                            CookieManager.getInstance().setCookie("https://m.weibo.cn", trimmed);
                        }
                    }
                }
            }

            if (cookieStr == null || cookieStr.isEmpty()) {
                postFailure(callback, "No Weibo login cookies. Please re-authorize.");
                return;
            }

            String url = "https://m.weibo.cn/api/container/getIndex"
                    + "?type=uid&value=" + uid
                    + "&containerid=107603" + uid
                    + "&page=" + page;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .addHeader("Cookie", cookieStr)
                    .addHeader("X-Requested-With", "XMLHttpRequest")
                    .addHeader("Referer", "https://m.weibo.cn/u/" + uid)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Mobile API request failed: " + e.getMessage());
                    postFailure(callback, "Mobile API error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful()) {
                        List<User.Status> list = WeiboParser.parseMobileStatusList(body);
                        Log.d(TAG, "Mobile API returned " + list.size() + " statuses (page=" + page + ")");
                        postSuccess(callback, list);
                    } else {
                        Log.e(TAG, "Mobile API HTTP " + response.code());
                        postFailure(callback, "Mobile API HTTP " + response.code());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Mobile API exception: " + e.getMessage());
            postFailure(callback, "Mobile API error: " + e.getMessage());
        }
    }

    // ==================== Fetch All ====================

    public void fetchAllWeibos(final int countPerPage,
                                final ApiCallback<List<User.Status>> finalCallback,
                                final ApiCallback<String> progressCallback) {
        final List<User.Status> allStatuses = new ArrayList<>();
        fetchMobilePage(1, allStatuses, finalCallback, progressCallback);
    }

    private void fetchMobilePage(final int page, final List<User.Status> accumulated,
                                  final ApiCallback<List<User.Status>> finalCallback,
                                  final ApiCallback<String> progressCallback) {
        fetchMobileUserTimeline(page, 50, new ApiCallback<List<User.Status>>() {
            @Override
            public void onSuccess(List<User.Status> result) {
                if (result.isEmpty()) {
                    Log.d(TAG, "Mobile fetch complete: " + accumulated.size() + " total weibos");
                    postSuccess(finalCallback, accumulated);
                    return;
                }
                accumulated.addAll(result);
                postSuccess(progressCallback, "Fetched " + accumulated.size() + " weibos...");

                if (result.size() < 5) {
                    postSuccess(finalCallback, accumulated);
                } else {
                    // Delay between pages to avoid rate limiting
                    final int nextPage = page + 1;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            fetchMobilePage(nextPage, accumulated, finalCallback, progressCallback);
                        }
                    }, 1500);
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                if (!accumulated.isEmpty()) {
                    postSuccess(finalCallback, accumulated);
                } else {
                    postFailure(finalCallback, errorMsg);
                }
            }
        });
    }

    // ==================== Helpers ====================

    private void executeRequest(String url, ApiCallback<String> callback) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("User-Agent", "WeiboPast-Android")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Request failed: " + e.getMessage());
                callback.onFailure("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    callback.onSuccess(body);
                } else {
                    Log.e(TAG, "HTTP " + response.code() + ": " + body);
                    callback.onFailure("HTTP " + response.code() + ": " + body);
                }
            }
        });
    }

    private <T> void postSuccess(final ApiCallback<T> callback, final T result) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() { callback.onSuccess(result); }
        });
    }

    private void postFailure(final ApiCallback<?> callback, final String errorMsg) {
        if (callback == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() { callback.onFailure(errorMsg); }
        });
    }

    public static String getLargeImageUrl(String thumbnailUrl) {
        if (thumbnailUrl == null) return null;
        return thumbnailUrl.replace("/thumbnail/", "/large/");
    }

    public static String getMiddleImageUrl(String thumbnailUrl) {
        if (thumbnailUrl == null) return null;
        return thumbnailUrl.replace("/thumbnail/", "/bmiddle/");
    }
}
