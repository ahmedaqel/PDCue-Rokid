package com.rsi.pdcue;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.net.Uri;
import android.content.Intent;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private static final int FILE_CHOOSER = 9010;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params){
                if(fileCallback!=null) fileCallback.onReceiveValue(null);
                fileCallback=cb;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER); return true; }
                catch(Exception e){ fileCallback=null; Toast.makeText(MainActivity.this,"File picker unavailable",Toast.LENGTH_SHORT).show(); return false; }
            }
        });
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==FILE_CHOOSER && fileCallback!=null){
            Uri[] result=WebChromeClient.FileChooserParams.parseResult(resultCode,data);
            fileCallback.onReceiveValue(result); fileCallback=null;
        }
    }

    @Override public void onBackPressed(){
        if(webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
