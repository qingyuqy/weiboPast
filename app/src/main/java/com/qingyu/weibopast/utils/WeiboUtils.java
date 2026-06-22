package com.qingyu.weibopast.utils;

import android.text.Html;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utility methods for weibo data processing
 */
public class WeiboUtils {

    /**
     * Format Weibo date string to readable format
     * Input: "Tue May 01 00:00:00 +0800 2018"
     * Output: "2018-05-01"
     */
    public static String formatDate(String weiboDate) {
        if (weiboDate == null || weiboDate.isEmpty()) return "";
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat(
                    "EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
            SimpleDateFormat outputFormat = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.ENGLISH);
            Date date = inputFormat.parse(weiboDate);
            return outputFormat.format(date);
        } catch (ParseException e) {
            return weiboDate;
        }
    }

    /**
     * Format Weibo date to short format for list display
     */
    public static String formatDateTime(String weiboDate) {
        if (weiboDate == null || weiboDate.isEmpty()) return "";
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat(
                    "EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
            SimpleDateFormat outputFormat = new SimpleDateFormat(
                    "MM-dd HH:mm", Locale.ENGLISH);
            Date date = inputFormat.parse(weiboDate);
            return outputFormat.format(date);
        } catch (ParseException e) {
            return weiboDate;
        }
    }

    /**
     * Extract date-only part for grouping weibos by day
     */
    public static String extractDate(String weiboDate) {
        if (weiboDate == null || weiboDate.isEmpty()) return "";
        try {
            SimpleDateFormat inputFormat = new SimpleDateFormat(
                    "EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
            SimpleDateFormat dateFormat = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.ENGLISH);
            Date date = inputFormat.parse(weiboDate);
            return dateFormat.format(date);
        } catch (ParseException e) {
            return weiboDate.length() >= 10 ? weiboDate.substring(0, 10) : weiboDate;
        }
    }

    /**
     * Strip HTML tags from source string
     */
    public static String stripSource(String source) {
        if (source == null || source.isEmpty()) return "";
        return Html.fromHtml(source).toString().trim();
    }

    /**
     * Format count numbers with units
     */
    public static String formatCount(int count) {
        if (count >= 10000) {
            return String.format(Locale.ENGLISH, "%.1fk", count / 10000.0);
        }
        return String.valueOf(count);
    }

    /**
     * Check if two date strings belong to the same day
     */
    public static boolean isSameDay(String date1, String date2) {
        return extractDate(date1).equals(extractDate(date2));
    }

    /**
     * Get filename-safe date string for export file naming
     */
    public static String getExportFileName(String screenName) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String safeName = (screenName != null) ? screenName.replaceAll("[^a-zA-Z0-9_]", "_") : "weibo";
        return safeName + "_memories_" + timestamp + ".pdf";
    }
}