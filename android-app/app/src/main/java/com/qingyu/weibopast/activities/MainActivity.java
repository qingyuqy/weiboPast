package com.qingyu.weibopast.activities;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.qingyu.weibopast.R;
import com.qingyu.weibopast.beans.User;
import com.qingyu.weibopast.network.WeiboApiClient;
import com.qingyu.weibopast.activities.LoginActivity;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private WeiboApiClient apiClient;
    private TextView tvUserInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        init();
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_camera) {
            // View timeline
            startActivity(new Intent(this, TimelineActivity.class));
        } else if (id == R.id.nav_gallery) {
            // Export as PDF
            startActivity(new Intent(this, ExportActivity.class));
        } else if (id == R.id.nav_slideshow) {
            // Reload user info
            loadUserInfo();
        } else if (id == R.id.nav_manage) {
            refreshWeiboSession();
        } else if (id == R.id.nav_share) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, "Check out my Weibo Memory Book!");
            startActivity(Intent.createChooser(share, "Share via"));
        } else if (id == R.id.nav_send) {
            Toast.makeText(this, "Send - Coming soon", Toast.LENGTH_SHORT).show();
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    public void init(){
        apiClient = new WeiboApiClient(this);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, ExportActivity.class));
            }
        });

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        // Update nav drawer menu labels
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.nav_camera).setTitle("My Timeline");
        menu.findItem(R.id.nav_gallery).setTitle("Export as PDF");
        menu.findItem(R.id.nav_slideshow).setTitle("Refresh Profile");
        menu.findItem(R.id.nav_manage).setTitle("Refresh Session");

        loadUserInfo();
    }

    private void refreshWeiboSession() {
        // Open a WebView to the Weibo OAuth page, which will use the existing login session
        // and redirect through passport, establishing cookies for the correct user.
        // We load a lightweight OAuth page that auto-redirects if already logged in.
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.setWebViewClient(new android.webkit.WebViewClient() {
            private boolean cookiesSaved = false;

            @Override
            public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (!cookiesSaved && url.contains("sina.com.cn/?code=")) {
                    cookiesSaved = true;
                }
            }

            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                if (cookiesSaved) return;
                // Wait a bit then try to get cookies from m.weibo.cn and passport domains
                String cookieStr = android.webkit.CookieManager.getInstance().getCookie("https://m.weibo.cn");
                if (cookieStr != null && !cookieStr.isEmpty()) {
                    getSharedPreferences("weibo_token", MODE_PRIVATE)
                        .edit().putString("mweibo_cookie", cookieStr).apply();
                    Log.i("MainActivity", "Refreshed m.weibo.cn cookies");
                    cookiesSaved = true;
                    android.widget.Toast.makeText(MainActivity.this,
                        "Session refreshed!", android.widget.Toast.LENGTH_SHORT).show();
                }
                // Try loading the authorize page if we havent gotten cookies yet
                if (!cookiesSaved && url.contains("m.weibo.cn")) {
                    // Still loading, let it finish
                }
            }
        });
        // Load the OAuth authorize page - this will auto-redirect if the user is already logged in
        wv.loadUrl("https://api.weibo.com/oauth2/authorize?client_id="
            + com.qingyu.weibopast.constants.APPConstants.APP_KEY
            + "&response_type=code&redirect_uri="
            + android.net.Uri.encode(com.qingyu.weibopast.constants.APPConstants.REDIRECT_URL)
            + "&display=mobile");
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(1, 1);
        addContentView(wv, params);
    }

    private void loadUserInfo() {
        apiClient.getUserInfo(new com.qingyu.weibopast.network.ApiCallback<User>() {
            @Override
            public void onSuccess(User user) {
                NavigationView navigationView = findViewById(R.id.nav_view);
                View headerView = navigationView.getHeaderView(0);
                if (headerView != null) {
                    TextView tvName = headerView.findViewById(R.id.textView);
                    if (tvName != null) {
                        tvName.setText(user.getScreen_name() + " @" + user.getName());
                    }
                }
            }

            @Override
            public void onFailure(String errorMsg) {
                // silently handle - user can see in nav header
            }
        });
    }
}

