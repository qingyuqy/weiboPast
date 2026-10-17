package com.qingyu.weibopast.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.view.ViewGroup;

import com.qingyu.weibopast.R;
import com.qingyu.weibopast.beans.User;
import com.qingyu.weibopast.export.PdfExporter;
import com.qingyu.weibopast.network.WeiboApiClient;

import java.io.File;
import java.util.List;

public class ExportActivity extends AppCompatActivity {

    private TextView tvUserName, tvUserStats, tvProgress;
    private Button btnFetch, btnExport, btnOpen;
    private ViewGroup cardProgress;
    private ProgressBar progressExport;
    private SwitchCompat switchImages, switchStats;

    private WeiboApiClient apiClient;
    private User currentUser;
    private List<User.Status> allStatuses;
    private File exportedPdfFile;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Export as PDF");
        }

        apiClient = new WeiboApiClient(this);
        initViews();
        loadUserInfo();
    }

    private void initViews() {
        tvUserName = findViewById(R.id.tv_user_name);
        tvUserStats = findViewById(R.id.tv_user_stats);
        tvProgress = findViewById(R.id.tv_progress);
        btnFetch = findViewById(R.id.btn_fetch_weibos);
        btnExport = findViewById(R.id.btn_export_pdf);
        btnOpen = findViewById(R.id.btn_open_pdf);
        cardProgress = findViewById(R.id.card_progress);
        progressExport = findViewById(R.id.progress_export);
        switchImages = findViewById(R.id.switch_include_images);
        switchStats = findViewById(R.id.switch_include_stats);

        btnFetch.setOnClickListener(v -> fetchAllWeibos());
        btnExport.setOnClickListener(v -> exportPdf());
        btnOpen.setOnClickListener(v -> openPdf());
    }

    private void loadUserInfo() {
        apiClient.getUserInfo(new com.qingyu.weibopast.network.ApiCallback<User>() {
            @Override
            public void onSuccess(User user) {
                currentUser = user;
                tvUserName.setText(user.getScreen_name() + " @" + user.getName());
                tvUserStats.setText(user.getStatuses_count() + " weibos | "
                        + user.getFollowers_count() + " followers | "
                        + user.getFriends_count() + " following");
                btnFetch.setText("1. Fetch All Weibos (" + user.getStatuses_count() + " total)");
            }

            @Override
            public void onFailure(String errorMsg) {
                tvUserName.setText("Failed to load user info");
                tvUserStats.setText(errorMsg);
            }
        });
    }

    private void fetchAllWeibos() {
        btnFetch.setEnabled(false);
        btnFetch.setText("Fetching...");
        cardProgress.setVisibility(View.VISIBLE);
        tvProgress.setText("Starting to fetch all weibos...");

        apiClient.fetchAllWeibos(100, new com.qingyu.weibopast.network.ApiCallback<List<User.Status>>() {
            @Override
            public void onSuccess(List<User.Status> result) {
                allStatuses = result;
                btnFetch.setEnabled(true);
                btnFetch.setText("1. Fetched " + result.size() + " weibos OK");
                btnExport.setEnabled(true);
                cardProgress.setVisibility(View.GONE);
                Toast.makeText(ExportActivity.this,
                        "Successfully fetched " + result.size() + " weibos!", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(String errorMsg) {
                btnFetch.setEnabled(true);
                btnFetch.setText("1. Retry Fetch (failed)");
                cardProgress.setVisibility(View.GONE);
                Toast.makeText(ExportActivity.this,
                        "Fetch failed: " + errorMsg, Toast.LENGTH_SHORT).show();
            }
        }, new com.qingyu.weibopast.network.ApiCallback<String>() {
            @Override
            public void onSuccess(String progress) {
                tvProgress.setText(progress);
            }

            @Override
            public void onFailure(String errorMsg) {
                // ignore intermediate progress errors
            }
        });
    }

    private void exportPdf() {
        if (allStatuses == null || allStatuses.isEmpty()) {
            Toast.makeText(this, "No weibos to export. Fetch first!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentUser == null) {
            Toast.makeText(this, "User info not loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }

        btnExport.setEnabled(false);
        btnExport.setText("Exporting...");
        cardProgress.setVisibility(View.VISIBLE);
        progressExport.setIndeterminate(false);
        progressExport.setMax(allStatuses.size());
        progressExport.setProgress(0);

        PdfExporter exporter = new PdfExporter(this);
        exporter.setIncludeImages(switchImages.isChecked());
        exporter.setIncludeStats(switchStats.isChecked());

        exporter.exportToPdf(currentUser, allStatuses, new PdfExporter.ExportProgressCallback() {
            @Override
            public void onProgress(String message, int current, int total) {
                runOnUiThread(() -> {
                    tvProgress.setText(message);
                    if (total > 0) {
                        progressExport.setMax(total);
                        progressExport.setProgress(current);
                    }
                });
            }

            @Override
            public void onComplete(File file) {
                runOnUiThread(() -> {
                    exportedPdfFile = file;
                    btnExport.setEnabled(true);
                    btnExport.setText("2. Export Complete OK");
                    btnOpen.setEnabled(true);
                    cardProgress.setVisibility(View.GONE);
                    Toast.makeText(ExportActivity.this,
                            "PDF saved to: " + file.getName(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnExport.setEnabled(true);
                    btnExport.setText("2. Retry Export (failed)");
                    cardProgress.setVisibility(View.GONE);
                    Toast.makeText(ExportActivity.this,
                            "Export failed: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openPdf() {
        if (exportedPdfFile == null || !exportedPdfFile.exists()) {
            Toast.makeText(this, "PDF file not found. Export first!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = Uri.fromFile(exportedPdfFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            startActivity(Intent.createChooser(intent, "Open PDF with"));
        } catch (Exception e) {
            Toast.makeText(this, "No PDF viewer found: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

