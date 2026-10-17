package com.qingyu.weibopast.activities;
import android.content.Intent;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.qingyu.weibopast.R;
import com.qingyu.weibopast.adapters.StatusAdapter;
import com.qingyu.weibopast.beans.User;
import com.qingyu.weibopast.network.ApiCallback;
import com.qingyu.weibopast.network.WeiboApiClient;

import java.util.ArrayList;
import java.util.List;

public class TimelineActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private StatusAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvEmpty;

    private WeiboApiClient apiClient;
    private List<User.Status> statuses = new ArrayList<>();
    private int currentPage = 1;
    private static final int COUNT_PER_PAGE = 50;
    private boolean isLoadingMore = false;
    private boolean hasMore = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timeline);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("My Weibos");
        }

        apiClient = new WeiboApiClient(this);
        loadUserInfo();

        initViews();
        loadWeibos(1);
    }

    private void loadUserInfo() {
        apiClient.getUserInfo(new com.qingyu.weibopast.network.ApiCallback<User>() {
            @Override
            public void onSuccess(User user) {
                String avatar = user.getAvatar_large();
                if (avatar == null || avatar.isEmpty()) avatar = user.getProfile_image_url();
                adapter.setUserInfo(user.getScreen_name(), avatar);
            }

            @Override
            public void onFailure(String errorMsg) {
                // silent
            }
        });
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_timeline);
        progressBar = findViewById(R.id.progress_loading);
        tvEmpty = findViewById(R.id.tv_empty);
        swipeRefresh = findViewById(R.id.swipe_refresh);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StatusAdapter(this);
        adapter.setStatuses(statuses);
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(() -> {
            currentPage = 1;
            hasMore = true;
            loadWeibos(1);
        });

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && hasMore && !isLoadingMore) {
                    int visibleCount = lm.getChildCount();
                    int totalCount = lm.getItemCount();
                    int firstVisible = lm.findFirstVisibleItemPosition();
                    if (visibleCount + firstVisible >= totalCount - 5) {
                        loadMore();
                    }
                }
            }
        });
    }

    private void loadWeibos(int page) {
        if (page == 1) {
            progressBar.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
        }

        apiClient.getUserTimeline(page, COUNT_PER_PAGE, new ApiCallback<List<User.Status>>() {
            @Override
            public void onSuccess(List<User.Status> result) {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                isLoadingMore = false;

                if (page == 1) {
                    statuses.clear();
                }

                if (result.isEmpty()) {
                    hasMore = false;
                } else {
                    statuses.addAll(result);
                }

                adapter.setStatuses(statuses);

                if (statuses.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                isLoadingMore = false;
                runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(TimelineActivity.this,
                                        "Failed to load: " + errorMsg, Toast.LENGTH_SHORT).show();
                            }
                        });
                if (statuses.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void loadMore() {
        if (isLoadingMore || !hasMore) return;
        isLoadingMore = true;
        currentPage++;
        loadWeibos(currentPage);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.timeline_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == R.id.action_export) {
            startActivity(new Intent(this, ExportActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

