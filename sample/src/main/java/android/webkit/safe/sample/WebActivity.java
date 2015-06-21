package android.webkit.safe.sample;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.safe.SafeWebChromeClient;
import android.webkit.safe.SafeWebView;

public class WebActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView wv = new SafeWebView(this);
        setContentView(wv);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        wv.addJavascriptInterface(new JavaScriptInterface(wv), "Android");
        wv.setWebChromeClient(new InnerChromeClient());
        wv.loadUrl("file:///android_asset/test.html");
    }

    public class InnerChromeClient extends SafeWebChromeClient {

        @Override
        public void onProgressChanged (WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress); // 务必放在方法体的第一行执行；
            // to do your work
            // ...
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            // to do your work
            // ...
            return super.onJsPrompt(view, url, message, defaultValue, result); // 务必放在方法体的最后一行执行，或者用if判断也行；
        }
    }
}
