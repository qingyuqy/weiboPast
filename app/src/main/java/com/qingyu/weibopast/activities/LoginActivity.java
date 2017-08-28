package com.qingyu.weibopast.activities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.app.LoaderManager.LoaderCallbacks;

import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;

import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.qingyu.weibopast.R;
import com.qingyu.weibopast.constants.APPConstants;
import com.sina.weibo.sdk.WbSdk;
import com.sina.weibo.sdk.auth.AccessTokenKeeper;
import com.sina.weibo.sdk.auth.AuthInfo;
import com.sina.weibo.sdk.auth.Oauth2AccessToken;
import com.sina.weibo.sdk.auth.WbAuthListener;
import com.sina.weibo.sdk.auth.WbConnectErrorMessage;
import com.sina.weibo.sdk.auth.sso.SsoHandler;
import com.sina.weibo.sdk.exception.WeiboException;
import com.sina.weibo.sdk.net.RequestListener;

import static android.Manifest.permission.READ_CONTACTS;

/**
 * A login screen that offers login via email/password.
 */
public class LoginActivity extends AppCompatActivity {

    // UI references.
    private View mProgressView;
    private View mLoginFormView;
    private Intent intent;

    /** 封装了 "access_token"，"expires_in"，"refresh_token"，并提供了他们的管理功能  */
    private Oauth2AccessToken mAccessToken;
    /** 注意：SsoHandler 仅当 SDK 支持 SSO 时有效 */
    private SsoHandler mSsoHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        init();

    }

    //override onActivityResult to for weibo sso auth callback
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (mSsoHandler != null) {
            mSsoHandler.authorizeCallBack(requestCode, resultCode, data);
        }
    }

    public void init(){
        mAccessToken = AccessTokenKeeper.readAccessToken(this);
        intent = new Intent(this,MainActivity.class);
        if(isAuthorized()){
            if(isExpired()){
                refreshToken();
            }else{
                startActivity(intent);
            }

        }else{
            initView();
        }
    }

    public void initView(){
        Button btSignIn = (Button) findViewById(R.id.btSignIn);
        btSignIn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                weiboAuth();
            }
        });

        mLoginFormView = findViewById(R.id.login_form);
        mProgressView = findViewById(R.id.login_progress);
    }

    //check if authorized before
    public boolean isAuthorized(){
       return mAccessToken.isSessionValid();
    }
    //check if access token expired
    public boolean isExpired(){
        long now = new Date().getTime();
        return mAccessToken.getExpiresTime() > now ;
    }

    //refresh token if expried
    public void refreshToken(){
        RequestListener requestListener = new RequestListener() {
            @Override
            public void onComplete(String s) {
                startActivity(intent);
            }

            @Override
            public void onWeiboException(WeiboException e) {
                Toast.makeText(LoginActivity.this,
                        "Refresh token failed" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        };
        AccessTokenKeeper.refreshToken(APPConstants.APP_KEY,this,requestListener);
    }

    //Weibo SSO auth
    public void weiboAuth() {
        WbSdk.install(this,new AuthInfo(this,  APPConstants.APP_KEY,  APPConstants.REDIRECT_URL, APPConstants.SCOPE));
        mSsoHandler = new SsoHandler(this);
        mSsoHandler. authorize(new WbAuthListener() {
            @Override
            public void onSuccess(final Oauth2AccessToken token) {
                LoginActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAccessToken = token;
                        if (mAccessToken.isSessionValid()) {
                            // 保存 Token 到 SharedPreferences
                            AccessTokenKeeper.writeAccessToken(LoginActivity.this, mAccessToken);
                            Toast.makeText(LoginActivity.this,
                                    "Auth successfully", Toast.LENGTH_SHORT).show();
                            startActivity(intent);
                        }
                    }
                });
            }

            @Override
            public void cancel() {
                Toast.makeText(LoginActivity.this,
                        "Auth cancelled", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(WbConnectErrorMessage errorMessage) {
                Toast.makeText(LoginActivity.this, "Auth failed" + errorMessage.getErrorMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean isEmailValid(String email) {
        //TODO: Replace this with your own logic
        return email.contains("@");
    }

    private boolean isPasswordValid(String password) {
        //TODO: Replace this with your own logic
        return password.length() > 4;
    }

    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private void showProgress(final boolean show) {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
            mLoginFormView.animate().setDuration(shortAnimTime).alpha(
                    show ? 0 : 1).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
                }
            });

            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mProgressView.animate().setDuration(shortAnimTime).alpha(
                    show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });
        } else {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }


}

