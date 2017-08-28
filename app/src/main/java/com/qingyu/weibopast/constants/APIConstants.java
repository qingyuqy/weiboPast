package com.qingyu.weibopast.constants;

/**
 * Created by QingYu on 2017/8/26.
 */

public class APIConstants {
    public static String HTTP_METHOD_GET = "GET";
    public static String HTTP_METHOD_POST = "POST";
    public static String HTTP_SERVER_URL = "https://api.weibo.com/2/";

    //User
    public static String API_USER_ME = "users/show.json";
    public static String API_WEIBO_PUBLIC = "statuses/public_timeline.json";
    //public static String API_WEIBO_ME = "statuses/repost_by_me"; //invalid
    public static String API_WEIBO_ME = "statuses/user_timeline.json";




}
