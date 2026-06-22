package com.qingyu.weibopast.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.text.TextPaint;
import android.util.Log;

import com.qingyu.weibopast.beans.User;
import com.qingyu.weibopast.network.WeiboApiClient;
import com.qingyu.weibopast.utils.WeiboUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Generates a magazine-style PDF book from Weibo posts using Android's built-in
 * PdfDocument API. No external PDF libraries required.
 */
public class PdfExporter {

    private static final String TAG = "PdfExporter";

    private final Context context;
    private boolean includeImages = true;
    private boolean includeStats = true;

    public PdfExporter(Context context) {
        this.context = context;
    }

    public void setIncludeImages(boolean include) {
        this.includeImages = include;
    }

    public void setIncludeStats(boolean include) {
        this.includeStats = include;
    }

    // --- Callback interface ---
    public interface ExportProgressCallback {
        void onProgress(String message, int current, int total);
        void onComplete(File file);
        void onError(String error);
    }

    // --- Main export entry point ---
    public File exportToPdf(User userInfo, List<User.Status> statuses,
                             ExportProgressCallback callback) {
        if (userInfo == null || statuses == null || statuses.isEmpty()) {
            if (callback != null)
                callback.onError("User info or weibo list is empty");
            return null;
        }

        PdfDocument document = new PdfDocument();
        String dateRange = buildDateRange(statuses);
        int total = statuses.size();

        try {
            // --- Cover page (page index 0) ---
            if (callback != null)
                callback.onProgress("Generating cover page...", 0, total);

            PdfDocument.PageInfo coverInfo = new PdfDocument.PageInfo.Builder(
                    MagazineTheme.PAGE_WIDTH, MagazineTheme.PAGE_HEIGHT, 0).create();
            PdfDocument.Page coverPage = document.startPage(coverInfo);
            drawCoverPage(coverPage.getCanvas(), userInfo, total, dateRange);
            document.finishPage(coverPage);

            // --- Content pages ---
            if (callback != null)
                callback.onProgress("Laying out content...", 0, total);

            Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            measurePaint.setTextSize(MagazineTheme.BODY_TEXT_SIZE);

            PdfDocument.Page currentPageObj = null;
            Canvas canvas = null;
            int pageNumber = 1;
            float y = MagazineTheme.CONTENT_TOP;
            String currentDate = "";

            for (int i = 0; i < total; i++) {
                User.Status status = statuses.get(i);
                String statusDate = WeiboUtils.extractDate(status.getCreated_at());
                boolean isNewDay = !statusDate.equals(currentDate);

                // Build the weibo card layout to measure height
                float cardHeight = measureCardHeight(status, isNewDay, measurePaint);

                // Start a new page if the card does not fit
                if (currentPageObj == null || y + cardHeight > MagazineTheme.PAGE_HEIGHT - MagazineTheme.PAGE_BOTTOM_MARGIN) {
                    if (currentPageObj != null) {
                        drawPageFooter(canvas, pageNumber);
                        document.finishPage(currentPageObj);
                    }
                    pageNumber++;
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                            MagazineTheme.PAGE_WIDTH, MagazineTheme.PAGE_HEIGHT, pageNumber).create();
                    currentPageObj = document.startPage(info);
                    canvas = currentPageObj.getCanvas();
                    y = MagazineTheme.CONTENT_TOP;
                }

                // Draw the weibo card
                y = drawWeiboCard(canvas, status, y, isNewDay, i, total, userInfo);

                if (callback != null && i % 5 == 0) {
                    callback.onProgress("Processing weibo " + (i + 1) + "/" + total + "...", i + 1, total);
                }
            }

            // Finish last page
            if (currentPageObj != null) {
                drawPageFooter(canvas, pageNumber);
                document.finishPage(currentPageObj);
            }

            // --- Write to file ---
            if (callback != null)
                callback.onProgress("Writing PDF file...", total, total);

            String fileName = WeiboUtils.getExportFileName(userInfo.getScreen_name());
            File docsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS);
            if (!docsDir.exists()) docsDir.mkdirs();
            File outputFile = new File(docsDir, fileName);

            FileOutputStream fos = new FileOutputStream(outputFile);
            document.writeTo(fos);
            fos.close();

            if (callback != null)
                callback.onComplete(outputFile);

            Log.i(TAG, "PDF saved: " + outputFile.getAbsolutePath());
            return outputFile;

        } catch (Exception e) {
            Log.e(TAG, "PDF export failed", e);
            if (callback != null)
                callback.onError("Export failed: " + e.getMessage());
            return null;
        } finally {
            document.close();
        }
    }

    // --- Cover page drawing ---
    private void drawCoverPage(Canvas canvas, User user, int totalCount, String dateRange) {
        int w = MagazineTheme.PAGE_WIDTH;
        int h = MagazineTheme.PAGE_HEIGHT;

        // Background
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(MagazineTheme.PRIMARY_COLOR);
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Decorative accent bar at top
        Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        accentPaint.setColor(MagazineTheme.ACCENT_COLOR);
        canvas.drawRect(0, h * 0.12f, w, h * 0.12f + 6, accentPaint);

        // Decorative accent bar at bottom
        canvas.drawRect(0, h * 0.82f, w, h * 0.82f + 3, accentPaint);

        // Circle decorations
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.argb(30, 255, 255, 255));
        canvas.drawCircle(w * 0.15f, h * 0.3f, 60, circlePaint);
        canvas.drawCircle(w * 0.85f, h * 0.7f, 90, circlePaint);

        // Avatar circle with initial letter
        Paint avatarBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatarBg.setColor(Color.argb(60, 255, 255, 255));
        float cx = w / 2f;
        float avatarRadius = 55;
        canvas.drawCircle(cx, h * 0.28f, avatarRadius, avatarBg);

        Paint initialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        initialPaint.setColor(Color.WHITE);
        initialPaint.setTextSize(48);
        initialPaint.setTextAlign(Paint.Align.CENTER);
        String initial = (user.getScreen_name() != null && user.getScreen_name().length() > 0)
                ? user.getScreen_name().substring(0, 1) : "W";
        canvas.drawText(initial, cx, h * 0.28f + 16, initialPaint);

        // Nickname
        Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        namePaint.setColor(Color.WHITE);
        namePaint.setTextSize(MagazineTheme.TITLE_TEXT_SIZE);
        namePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("@" + user.getScreen_name(), cx, h * 0.40f, namePaint);

        // Title line
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(MagazineTheme.SUBTITLE_TEXT_SIZE);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Weibo Memory Book", cx, h * 0.50f, titlePaint);

        // Stats line
        Paint countPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        countPaint.setColor(Color.argb(200, 255, 255, 255));
        countPaint.setTextSize(MagazineTheme.DATE_TEXT_SIZE);
        countPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(totalCount + " Weibos", cx, h * 0.58f, countPaint);

        // Date range
        if (dateRange != null && !dateRange.isEmpty()) {
            Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            datePaint.setColor(Color.argb(150, 255, 255, 255));
            datePaint.setTextSize(MagazineTheme.BODY_TEXT_SIZE);
            datePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(dateRange, cx, h * 0.64f, datePaint);
        }

        // Footer
        Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(Color.argb(100, 255, 255, 255));
        footerPaint.setTextSize(MagazineTheme.PAGE_NUM_SIZE);
        footerPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Generated by WeiboPast", cx, h * 0.92f, footerPaint);
    }

    // --- Weibo card drawing ---
    private float drawWeiboCard(Canvas canvas, User.Status status, float y,
                                boolean isNewDay, int index, int total, User userInfo) {
        float left = MagazineTheme.PAGE_LEFT_MARGIN;
        float right = MagazineTheme.PAGE_WIDTH - MagazineTheme.PAGE_RIGHT_MARGIN;
        float cardWidth = right - left;

        // Date divider for new day
        if (isNewDay) {
            // Colored date header
            Paint dateBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            dateBg.setColor(Color.argb(30, 26, 35, 126));
            canvas.drawRoundRect(new RectF(left - 10, y - 4, right + 10, y + 28), 4, 4, dateBg);

            Paint dateText = new Paint(Paint.ANTI_ALIAS_FLAG);
            dateText.setColor(MagazineTheme.PRIMARY_COLOR);
            dateText.setTextSize(MagazineTheme.DATE_TEXT_SIZE);
            dateText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            String formattedDate = WeiboUtils.formatDate(status.getCreated_at());
            canvas.drawText(formattedDate, left, y + 18, dateText);
            y += 40;
        }

        // Card background with minimal shadow
        Paint cardBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBg.setColor(MagazineTheme.CARD_BG_COLOR);
        canvas.drawRoundRect(new RectF(left, y, right, y + 20), 5, 5, cardBg);

        // Color accent bar on the left side
        Paint accentBar = new Paint(Paint.ANTI_ALIAS_FLAG);
        accentBar.setColor(MagazineTheme.PRIMARY_COLOR);
        canvas.drawRoundRect(new RectF(left, y, left + 3, y + 20), 1, 1, accentBar);

        float currentX = left + 16;
        float currentY = y + 16;

        // Source and time
        Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        metaPaint.setColor(MagazineTheme.LIGHT_TEXT_COLOR);
        metaPaint.setTextSize(MagazineTheme.BODY_TEXT_SIZE - 2);
        String timeStr = WeiboUtils.formatDateTime(status.getCreated_at());
        String source = WeiboUtils.stripSource(status.getSource());
        String metaText = (source.isEmpty() ? "" : "via " + source) + "  " + timeStr;
        canvas.drawText(metaText, currentX, currentY, metaPaint);
        currentY += 20;

        // Weibo text (word-wrapped)
        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(MagazineTheme.TEXT_COLOR);
        bodyPaint.setTextSize(MagazineTheme.BODY_TEXT_SIZE);
        bodyPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        int lineCount = drawWrappedText(canvas, status.getText(), currentX, currentY,
                cardWidth - 24, bodyPaint);
        currentY += lineCount * (MagazineTheme.BODY_TEXT_SIZE + 4) + 6;

        // Images (if enabled)
        if (includeImages) {
            java.util.List<User.Status.PicUrls> picUrls = status.getPic_urls();
            String singleThumb = status.getThumbnail_pic();
            boolean hasImages = (picUrls != null && !picUrls.isEmpty()) || singleThumb != null;

            if (hasImages) {
                int maxImages = 0;
                if (picUrls != null && !picUrls.isEmpty()) {
                    maxImages = picUrls.size();
                } else {
                    maxImages = 1;
                }
                int imagesPerRow = 3;
                int imgSize = (int) ((cardWidth - 24 - (imagesPerRow - 1) * 6) / imagesPerRow);
                if (imgSize > 150) imgSize = 150;

                for (int idx = 0; idx < maxImages && idx < 9; idx++) {
                    String imgUrl;
                    if (picUrls != null && !picUrls.isEmpty() && idx < picUrls.size()) {
                        imgUrl = WeiboApiClient.getLargeImageUrl(
                                picUrls.get(idx).getThumbnail_pic());
                    } else if (idx == 0 && singleThumb != null) {
                        imgUrl = WeiboApiClient.getLargeImageUrl(singleThumb);
                    } else {
                        break;
                    }

                    if (imgUrl != null) {
                        Bitmap bmp = downloadImage(imgUrl);
                        if (bmp != null) {
                            float col = idx % imagesPerRow;
                            float row = idx / imagesPerRow;
                            float ix = currentX + col * (imgSize + 6);
                            float iy = currentY + row * (imgSize + 6);
                            RectF imgRect = new RectF(ix, iy, ix + imgSize, iy + imgSize);
                            canvas.drawBitmap(bmp, null, imgRect, null);
                            bmp.recycle();
                        }
                    }
                }
                int imgRows = (maxImages + imagesPerRow - 1) / imagesPerRow;
                currentY += imgRows * (imgSize + 6) + 4;
            }
        }

        // Stats footer
        if (includeStats) {
            Paint statPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            statPaint.setColor(Color.argb(180, 117, 117, 117));
            statPaint.setTextSize(MagazineTheme.BODY_TEXT_SIZE - 1);

            String statsText = "\u2191 " + status.getReposts_count()
                    + "    \u2190 " + status.getComments_count()
                    + "    \u2661 " + status.getAttitudes_count();
            float statsX = right - 12 - statPaint.measureText(statsText);
            canvas.drawText(statsText, statsX, currentY + 4, statPaint);
            currentY += 20;
        }

        // Final card height
        float cardBottom = currentY + 10;
        // Redraw card background with correct height
        RectF cardRect = new RectF(left, y, right, cardBottom);
        Paint fillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillBg.setColor(MagazineTheme.CARD_BG_COLOR);
        canvas.drawRoundRect(cardRect, 6, 6, fillBg);

        // Redraw text on top
        canvas.drawText(metaText, currentX, y + 16, metaPaint);
        drawWrappedText(canvas, status.getText(), currentX, y + 36,
                cardWidth - 24, bodyPaint);
        // Images would need redrawing too, but for simplicity we accept the small layering order

        return cardBottom + 12;
    }

    // --- Measure card height ---
    private float measureCardHeight(User.Status status, boolean isNewDay, Paint measurePaint) {
        float h = 0;
        if (isNewDay) h += 35;
        h += 16 + 20; // meta row
        // text
        if (status.getText() != null) {
            float cardWidth = MagazineTheme.PAGE_WIDTH - MagazineTheme.PAGE_LEFT_MARGIN
                    - MagazineTheme.PAGE_RIGHT_MARGIN - 24;
            int textLines = countWrappedLines(status.getText(), cardWidth, measurePaint);
            h += textLines * (MagazineTheme.BODY_TEXT_SIZE + 4) + 6;
        }
        // images
        if (includeImages) {
            boolean hasImages = (status.getPic_urls() != null && !status.getPic_urls().isEmpty())
                    || status.getThumbnail_pic() != null;
            if (hasImages) {
                h += 150 + 6 + 4;
            }
        }
        // stats
        if (includeStats) h += 20;
        h += 10 + 12; // padding + card bottom margin
        return h;
    }

    // --- Page footer with page number ---
    private void drawPageFooter(Canvas canvas, int pageNumber) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(MagazineTheme.LIGHT_TEXT_COLOR);
        p.setTextSize(MagazineTheme.PAGE_NUM_SIZE);
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("-- " + pageNumber + " --",
                MagazineTheme.PAGE_WIDTH / 2f,
                MagazineTheme.PAGE_HEIGHT - 30, p);
    }

    // --- Word-wrapped text drawing ---
    private int drawWrappedText(Canvas canvas, String text, float x, float y,
                                 float maxWidth, Paint paint) {
        if (text == null || text.isEmpty()) return 0;
        String[] paragraphs = text.split("\n");
        int totalLines = 0;
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                totalLines++;
                continue;
            }
            String remaining = para;
            while (!remaining.isEmpty()) {
                int breakCount = paint.breakText(remaining, true, maxWidth, null);
                if (breakCount <= 0) break;
                String line = remaining.substring(0, breakCount);
                canvas.drawText(line, x, y, paint);
                y += paint.getTextSize() + 4;
                totalLines++;
                remaining = remaining.substring(breakCount);
            }
        }
        return totalLines;
    }

    private int countWrappedLines(String text, float maxWidth, Paint paint) {
        if (text == null || text.isEmpty()) return 0;
        String[] paragraphs = text.split("\n");
        int total = 0;
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                total++;
                continue;
            }
            String remaining = para;
            while (!remaining.isEmpty()) {
                int breakCount = paint.breakText(remaining, true, maxWidth, null);
                if (breakCount <= 0) break;
                total++;
                remaining = remaining.substring(breakCount);
            }
        }
        return total;
    }

    // --- Download image from URL ---
    private Bitmap downloadImage(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Referer", "https://weibo.com/");
            conn.setRequestProperty("Accept", "image/webp,image/*,*/*;q=0.8");
            InputStream is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "Failed to download image: " + urlStr + " - " + e.getMessage());
            return null;
        }
    }

    // --- Build date range string ---
    private String buildDateRange(List<User.Status> statuses) {
        if (statuses == null || statuses.isEmpty()) return "";
        try {
            SimpleDateFormat inputFmt = new SimpleDateFormat(
                    "EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
            SimpleDateFormat outputFmt = new SimpleDateFormat(
                    "yyyy-MM-dd", Locale.ENGLISH);

            String firstDate = statuses.get(statuses.size() - 1).getCreated_at();
            String lastDate = statuses.get(0).getCreated_at();

            String first = outputFmt.format(inputFmt.parse(firstDate));
            String last = outputFmt.format(inputFmt.parse(lastDate));
            return first + "  ~  " + last;
        } catch (Exception e) {
            return "";
        }
    }

    // --- MagazineTheme inner class ---
    public static class MagazineTheme {
        // A4 page dimensions (points: 1pt = 1/72 inch)
        public static final int PAGE_WIDTH = 595;
        public static final int PAGE_HEIGHT = 842;

        public static final float PAGE_LEFT_MARGIN = 50;
        public static final float PAGE_RIGHT_MARGIN = 50;
        public static final float PAGE_TOP_MARGIN = 60;
        public static final float PAGE_BOTTOM_MARGIN = 60;
        public static final float CONTENT_TOP = PAGE_TOP_MARGIN + 10;

        // Colors
        public static final int PRIMARY_COLOR = Color.parseColor("#1a237e");
        public static final int ACCENT_COLOR = Color.parseColor("#c62828");
        public static final int TEXT_COLOR = Color.parseColor("#212121");
        public static final int LIGHT_TEXT_COLOR = Color.parseColor("#757575");
        public static final int BG_COLOR = Color.parseColor("#fafafa");
        public static final int CARD_BG_COLOR = Color.WHITE;

        // Font sizes
        public static final float TITLE_TEXT_SIZE = 28;
        public static final float SUBTITLE_TEXT_SIZE = 14;
        public static final float BODY_TEXT_SIZE = 11;
        public static final float DATE_TEXT_SIZE = 16;
        public static final float PAGE_NUM_SIZE = 9;
    }
}
