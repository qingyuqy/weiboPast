package com.qingyu.weibopast.activities;

import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.util.Log;
import android.view.View;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.qingyu.weibopast.R;
import com.qingyu.weibopast.constants.APIConstants;
import com.sina.weibo.sdk.WbSdk;
import com.sina.weibo.sdk.auth.AccessTokenKeeper;
import com.sina.weibo.sdk.auth.AuthInfo;
import com.sina.weibo.sdk.auth.Oauth2AccessToken;
import com.sina.weibo.sdk.auth.WbAuthListener;
import com.sina.weibo.sdk.auth.WbConnectErrorMessage;
import com.sina.weibo.sdk.auth.sso.SsoHandler;
import com.sina.weibo.sdk.exception.WeiboException;
import com.sina.weibo.sdk.net.AsyncWeiboRunner;
import com.sina.weibo.sdk.net.RequestListener;
import com.sina.weibo.sdk.net.WeiboParameters;
import com.qingyu.weibopast.constants.APPConstants;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private Oauth2AccessToken mAccessToken;
    private String result;
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
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_camera) {
            // Handle the camera action
        } else if (id == R.id.nav_gallery) {

        } else if (id == R.id.nav_slideshow) {

        } else if (id == R.id.nav_manage) {

        } else if (id == R.id.nav_share) {

        } else if (id == R.id.nav_send) {

        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    public void init(){
        mAccessToken = AccessTokenKeeper.readAccessToken(this);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

            }
        });

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        final TextView tvTest = (TextView)findViewById(R.id.tvTest);
        Button btTest1 =(Button) findViewById(R.id.btTest1);
        Button btTest2 =(Button) findViewById(R.id.btTest2);

       btTest1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getWeiboMessage(APIConstants.API_USER_ME);
                Toast.makeText(MainActivity.this,
                        result, Toast.LENGTH_SHORT).show();
                tvTest.setText(result);
            }
        });
        btTest2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getWeiboMessage(APIConstants.API_WEIBO_PUBLIC);
                Toast.makeText(MainActivity.this,
                        result, Toast.LENGTH_SHORT).show();
                tvTest.setText(result);
            }
        });
    }

   public void getWeiboMessage(String api){

        RequestListener mInternalListener = new RequestListener() {
            @Override
            public void onComplete(String response) {
            Toast.makeText(MainActivity.this,
                        response, Toast.LENGTH_SHORT).show();
                result = response;
                Log.e("weibo",response);
            }
            @Override
            public void onWeiboException(WeiboException e) {
                Log.e("WeiboException",e.getMessage());
            }
        };

        AsyncWeiboRunner asyncWeiboRunner = new AsyncWeiboRunner(MainActivity.this);
        WeiboParameters weiboParameters = new WeiboParameters(APPConstants.APP_KEY);
        weiboParameters.put("access_token",mAccessToken.getToken());
        asyncWeiboRunner.requestAsync(APIConstants.HTTP_SERVER_URL + api,weiboParameters,APIConstants.HTTP_METHOD_GET,mInternalListener);

    }
}
