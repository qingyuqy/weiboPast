package com.qingyu.weibopast.network;

import com.qingyu.weibopast.beans.User;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Weibo API JSON responses into the model classes.
 */
public class WeiboParser {

    /**
     * Parses a full user JSON string into a User object.
     */
    public static User parseUser(String jsonString) {
        try {
            JSONObject obj = new JSONObject(jsonString);
            return parseUserFromJson(obj);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a single weibo Status from a JSONObject.
     */
    public static User.Status parseStatus(JSONObject obj) {
        if (obj == null) return null;
        try {
            User.Status status = new User.Status();
            status.setCreated_at(obj.optString("created_at", ""));
            status.setId(obj.optLong("id", 0));
            status.setMid(obj.optString("mid", ""));
            status.setIdstr(obj.optString("idstr", ""));
            status.setText(obj.optString("text", ""));
            status.setTextLength(obj.optInt("textLength", 0));
            status.setSource_allowclick(obj.optInt("source_allowclick", 0));
            status.setSource_type(obj.optInt("source_type", 0));
            status.setSource(obj.optString("source", ""));
            status.setFavorited(obj.optBoolean("favorited", false));
            status.setTruncated(obj.optBoolean("truncated", false));
            status.setIn_reply_to_status_id(obj.optString("in_reply_to_status_id", ""));
            status.setIn_reply_to_user_id(obj.optString("in_reply_to_user_id", ""));
            status.setIn_reply_to_screen_name(obj.optString("in_reply_to_screen_name", ""));
            status.setThumbnail_pic(obj.optString("thumbnail_pic", ""));
            status.setBmiddle_pic(obj.optString("bmiddle_pic", ""));
            status.setOriginal_pic(obj.optString("original_pic", ""));
            status.setGeo(obj.opt("geo"));
            status.setIs_vip_paid_status(obj.optBoolean("is_vip_paid_status", false));
            status.setIs_paid(obj.optBoolean("is_paid", false));
            status.setMblog_vip_type(obj.optInt("mblog_vip_type", 0));
            status.setReposts_count(obj.optInt("reposts_count", 0));
            status.setComments_count(obj.optInt("comments_count", 0));
            status.setAttitudes_count(obj.optInt("attitudes_count", 0));
            status.setIsLongText(obj.optBoolean("isLongText", false));
            status.setMlevel(obj.optInt("mlevel", 0));
            status.setBiz_feature(obj.optLong("biz_feature", 0));
            status.setHasActionTypeCard(obj.optInt("hasActionTypeCard", 0));
            status.setUserType(obj.optInt("userType", 0));
            status.setMore_info_type(obj.optInt("more_info_type", 0));
            status.setPositive_recom_flag(obj.optInt("positive_recom_flag", 0));
            status.setGif_ids(obj.optString("gif_ids", ""));
            status.setIs_show_bulletin(obj.optInt("is_show_bulletin", 0));

            // Parse nested Visible
            JSONObject visibleObj = obj.optJSONObject("visible");
            if (visibleObj != null) {
                User.Status.Visible visible = new User.Status.Visible();
                visible.setType(visibleObj.optInt("type", 0));
                visible.setList_id(visibleObj.optInt("list_id", 0));
                status.setVisible(visible);
            }

            // Parse nested CommentManageInfo
            JSONObject cmiObj = obj.optJSONObject("comment_manage_info");
            if (cmiObj != null) {
                User.Status.CommentManageInfo cmi = new User.Status.CommentManageInfo();
                cmi.setComment_permission_type(cmiObj.optInt("comment_permission_type", 0));
                status.setComment_manage_info(cmi);
            }

            // Parse pic_urls array
            JSONArray picUrlsArray = obj.optJSONArray("pic_urls");
            if (picUrlsArray != null) {
                List<User.Status.PicUrls> picUrlsList = new ArrayList<>(picUrlsArray.length());
                for (int i = 0; i < picUrlsArray.length(); i++) {
                    JSONObject picObj = picUrlsArray.optJSONObject(i);
                    if (picObj != null) {
                        User.Status.PicUrls picUrl = new User.Status.PicUrls();
                        picUrl.setThumbnail_pic(picObj.optString("thumbnail_pic", ""));
                        picUrlsList.add(picUrl);
                    }
                }
                status.setPic_urls(picUrlsList);
            }

            // Parse annotations array
            JSONArray annotationsArray = obj.optJSONArray("annotations");
            if (annotationsArray != null) {
                List<User.Status.Annotations> annotationsList = new ArrayList<>(annotationsArray.length());
                for (int i = 0; i < annotationsArray.length(); i++) {
                    JSONObject annObj = annotationsArray.optJSONObject(i);
                    if (annObj != null) {
                        User.Status.Annotations annotation = new User.Status.Annotations();
                        annotation.setClient_mblogid(annObj.optString("client_mblogid", ""));
                        annotation.setMapi_request(annObj.optBoolean("mapi_request", false));
                        annotationsList.add(annotation);
                    }
                }
                status.setAnnotations(annotationsList);
            }

            return status;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses the "statuses" array from a user_timeline JSON response string.
     */
    public static List<User.Status> parseStatusList(String jsonString) {
        List<User.Status> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(jsonString);
            JSONArray statuses = root.optJSONArray("statuses");
            if (statuses != null) {
                for (int i = 0; i < statuses.length(); i++) {
                    JSONObject obj = statuses.optJSONObject(i);
                    User.Status status = parseStatus(obj);
                    if (status != null) {
                        list.add(status);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /**
     * Extracts total_number from a user_timeline JSON response string.
     */
    /**
     * Parses m.weibo.cn API response (container/getIndex) into Status list.
     * Card type 9 contains mblog field with weibo data.
     */
    public static List<User.Status> parseMobileStatusList(String jsonString) {
        List<User.Status> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(jsonString);
            int ok = root.optInt("ok", 0);
            if (ok != 1) {
                if (ok == -100) {
                    // Captcha required - log it
                    android.util.Log.w("WeiboParser", "Mobile API captcha required");
                }
                return list;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) return list;
            JSONArray cards = data.optJSONArray("cards");
            if (cards == null) return list;
            for (int i = 0; i < cards.length(); i++) {
                JSONObject card = cards.optJSONObject(i);
                if (card == null) continue;
                int cardType = card.optInt("card_type", 0);
                if (cardType == 9) {
                    JSONObject mblog = card.optJSONObject("mblog");
                    if (mblog != null) {
                        User.Status status = parseStatus(mblog);
                        if (status != null) {
                            // m.weibo.cn stores pic_ids as array; construct pic_urls from them
                            JSONArray picIds = mblog.optJSONArray("pic_ids");
                            if (picIds != null && picIds.length() > 0 && status.getPic_urls() == null) {
                                List<User.Status.PicUrls> picUrlsList = new ArrayList<>(picIds.length());
                                for (int j = 0; j < picIds.length(); j++) {
                                    String picId = picIds.optString(j, "");
                                    if (!picId.isEmpty()) {
                                        User.Status.PicUrls picUrl = new User.Status.PicUrls();
                                        picUrl.setThumbnail_pic("https://wx3.sinaimg.cn/thumbnail/" + picId + ".jpg");
                                        picUrlsList.add(picUrl);
                                    }
                                }
                                if (!picUrlsList.isEmpty()) {
                                    status.setPic_urls(picUrlsList);
                                    status.setThumbnail_pic(picUrlsList.get(0).getThumbnail_pic());
                                    String firstId = picIds.optString(0, "");
                                    if (!firstId.isEmpty()) {
                                        status.setBmiddle_pic("https://wx3.sinaimg.cn/bmiddle/" + firstId + ".jpg");
                                        status.setOriginal_pic("https://wx3.sinaimg.cn/large/" + firstId + ".jpg");
                                    }
                                }
                            }
                            list.add(status);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /**
     * Parses total_number from m.weibo.cn API response.
     */
    public static int parseMobileTotalNumber(String jsonString) {
        try {
            JSONObject root = new JSONObject(jsonString);
            if (root.optInt("ok", 0) != 1) return 0;
            JSONObject data = root.optJSONObject("data");
            if (data == null) return 0;
            JSONObject cardListInfo = data.optJSONObject("cardlistInfo");
            if (cardListInfo != null) {
                return cardListInfo.optInt("total", 0);
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static int parseTotalNumber(String jsonString) {
        try {
            JSONObject root = new JSONObject(jsonString);
            return root.optInt("total_number", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Internal helper: parse a User from a JSONObject
     */
    private static User parseUserFromJson(JSONObject obj) {
        if (obj == null) return null;
        try {
            User user = new User();
            user.setId(obj.optInt("id", 0));
            user.setIdstr(obj.optString("idstr", ""));
            user.setClassX(obj.optInt("class", 0));
            user.setScreen_name(obj.optString("screen_name", ""));
            user.setName(obj.optString("name", ""));
            user.setProvince(obj.optString("province", ""));
            user.setCity(obj.optString("city", ""));
            user.setLocation(obj.optString("location", ""));
            user.setDescription(obj.optString("description", ""));
            user.setUrl(obj.optString("url", ""));
            user.setProfile_image_url(obj.optString("profile_image_url", ""));
            user.setCover_image(obj.optString("cover_image", ""));
            user.setCover_image_phone(obj.optString("cover_image_phone", ""));
            user.setProfile_url(obj.optString("profile_url", ""));
            user.setDomain(obj.optString("domain", ""));
            user.setWeihao(obj.optString("weihao", ""));
            user.setGender(obj.optString("gender", ""));
            user.setFollowers_count(obj.optInt("followers_count", 0));
            user.setFriends_count(obj.optInt("friends_count", 0));
            user.setPagefriends_count(obj.optInt("pagefriends_count", 0));
            user.setStatuses_count(obj.optInt("statuses_count", 0));
            user.setFavourites_count(obj.optInt("favourites_count", 0));
            user.setCreated_at(obj.optString("created_at", ""));
            user.setFollowing(obj.optBoolean("following", false));
            user.setAllow_all_act_msg(obj.optBoolean("allow_all_act_msg", false));
            user.setGeo_enabled(obj.optBoolean("geo_enabled", false));
            user.setVerified(obj.optBoolean("verified", false));
            user.setVerified_type(obj.optInt("verified_type", -1));
            user.setRemark(obj.optString("remark", ""));
            user.setPtype(obj.optInt("ptype", 0));
            user.setAllow_all_comment(obj.optBoolean("allow_all_comment", false));
            user.setAvatar_large(obj.optString("avatar_large", ""));
            user.setAvatar_hd(obj.optString("avatar_hd", ""));
            user.setVerified_reason(obj.optString("verified_reason", ""));
            user.setVerified_trade(obj.optString("verified_trade", ""));
            user.setVerified_reason_url(obj.optString("verified_reason_url", ""));
            user.setVerified_source(obj.optString("verified_source", ""));
            user.setVerified_source_url(obj.optString("verified_source_url", ""));
            user.setFollow_me(obj.optBoolean("follow_me", false));
            user.setOnline_status(obj.optInt("online_status", 0));
            user.setBi_followers_count(obj.optInt("bi_followers_count", 0));
            user.setLang(obj.optString("lang", "zh-cn"));
            user.setStar(obj.optInt("star", 0));
            user.setMbtype(obj.optInt("mbtype", 0));
            user.setMbrank(obj.optInt("mbrank", 0));
            user.setBlock_word(obj.optInt("block_word", 0));
            user.setBlock_app(obj.optInt("block_app", 0));
            user.setCredit_score(obj.optInt("credit_score", 0));
            user.setUser_ability(obj.optInt("user_ability", 0));
            user.setUrank(obj.optInt("urank", 0));
            user.setStory_read_state(obj.optInt("story_read_state", 0));
            user.setVclub_member(obj.optInt("vclub_member", 0));

            // Parse nested Insecurity
            JSONObject insecurityObj = obj.optJSONObject("insecurity");
            if (insecurityObj != null) {
                User.Insecurity insecurity = new User.Insecurity();
                insecurity.setSexual_content(insecurityObj.optBoolean("sexual_content", false));
                user.setInsecurity(insecurity);
            }

            // Parse nested status (latest weibo)
            JSONObject statusObj = obj.optJSONObject("status");
            if (statusObj != null) {
                user.setStatus(parseStatus(statusObj));
            }

            return user;
        } catch (Exception e) {
            return null;
        }
    }
}
