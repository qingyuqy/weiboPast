package com.qingyu.weibopast.constants;

/**
 * Weibo API endpoint constants
 */
public class APIConstants {
    public static String HTTP_METHOD_GET = "GET";
    public static String HTTP_METHOD_POST = "POST";
    public static String HTTP_SERVER_URL = "https://api.weibo.com/2/";
    public static String MOBILE_API_URL = "https://m.weibo.cn/api/container/getIndex";
    public static String MOBILE_API_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    // User APIs
    public static String API_USER_ME = "users/show.json";

    // Status / Weibo APIs
    public static String API_WEIBO_PUBLIC = "statuses/public_timeline.json";
    public static String API_WEIBO_ME = "statuses/user_timeline.json";
    public static String API_WEIBO_SHOW = "statuses/show.json";
    public static String API_WEIBO_COUNT = "statuses/count.json";

    // Image URL helpers
    public static String getLargePicUrl(String thumbnailUrl) {
        if (thumbnailUrl == null) return null;
        return thumbnailUrl.replace("/thumbnail/", "/large/");
    }

    public static String getMiddlePicUrl(String thumbnailUrl) {
        if (thumbnailUrl == null) return null;
        return thumbnailUrl.replace("/thumbnail/", "/bmiddle/");
    }
}
