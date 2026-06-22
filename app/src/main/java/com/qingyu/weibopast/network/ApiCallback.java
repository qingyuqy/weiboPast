package com.qingyu.weibopast.network;

/**
 * Generic callback for async Weibo API requests
 */
public interface ApiCallback<T> {
    void onSuccess(T result);
    void onFailure(String errorMsg);
}
