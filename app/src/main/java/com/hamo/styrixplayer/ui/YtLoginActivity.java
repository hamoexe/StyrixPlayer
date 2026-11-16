package com.hamo.styrixplayer.ui;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;

import com.hamo.styrixplayer.yt.YtAuth;

/**
 * Google's own sign-in page in a WebView. When it lands back on music.youtube.com with a finished
 * session, the cookies are saved and the screen closes. The app never sees your password.
 */
public class YtLoginActivity extends BaseActivity {

    private static final String LOGIN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&hl=en"
                    + "&continue=https%3A%2F%2Fmusic.youtube.com%2F";
    /** A plain Chrome identity: Google refuses sign-in in WebViews that announce themselves. */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Mobile Safari/537.36";

    private WebView web;
    private boolean done;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        padSystemBars(root, true, true);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(UA);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                checkSession(url);
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        web.loadUrl(LOGIN_URL);
    }

    private void checkSession(@Nullable String url) {
        if (done || url == null) return;
        String host = Uri.parse(url).getHost();
        if (host == null || !host.endsWith("music.youtube.com")) return;
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        String cookies = cm.getCookie("https://music.youtube.com");
        if (YtAuth.looksSignedIn(cookies)) {
            done = true;
            YtAuth.save(this, cookies);
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
