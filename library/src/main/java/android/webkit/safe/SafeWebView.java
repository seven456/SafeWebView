package android.webkit.safe;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhangguojun on 2015/6/21.
 */
public class SafeWebView extends WebView {
    private static final String TAG = "SafeWebView";
    private Map<String, JsCallJava> mJsCallJavas;
    private Map<Integer, String> mInjectJavaScripts;
    private SafeWebChromeClient mWebChromeClient;
    private SafeWebViewClient mWebViewClient;

    public SafeWebView(Context context) {
        this(context, null);
    }

    public SafeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        removeSearchBoxJavaBridge();

        // WebView跨源（加载本地文件）攻击分析：http://blogs.360.cn/360mobile/2014/09/22/webview%E8%B7%A8%E6%BA%90%E6%94%BB%E5%87%BB%E5%88%86%E6%9E%90/
        // 是否允许WebView使用File协议，移动版的Chrome默认禁止加载file协议的文件；
        getSettings().setAllowFileAccess(false);
    }

    /**
     * 经过大量的测试，按照以下方式才能保证JS脚本100%注入成功：
     * 1、在第一次loadUrl之前注入JS（在addJavascriptInterface里面注入即可）；
     * 2、在webViewClient.onPageStarted中都注入JS；
     * 3、在webChromeClient.onProgressChanged中都注入JS，并且不能通过自检查（onJsPrompt里面判断）JS是否注入成功来减少注入JS的次数，因为网页中的JS可以同时打开多个url导致无法控制检查的准确性；
     * 4、注入的JS中已经在脚本中检查注入的对象是否已经存在，避免注入对象被重新赋值导致网页引用该对象的方法时发生异常；
     *
     * @deprecated Android4.2.2及以上版本的addJavascriptInterface方法已经解决了安全问题，如果不使用“网页能将JS函数传到Java层”功能，不建议使用该类，毕竟系统的JS注入效率才是最高的；
     */
    @Override
    @Deprecated
    public void addJavascriptInterface(Object interfaceObj, String interfaceName) {
        if (mJsCallJavas == null) {
            mJsCallJavas = new HashMap<String, JsCallJava>();
        }
        mJsCallJavas.put(interfaceName, new JsCallJava(interfaceObj, interfaceName));
        setClient();
        if (mJsCallJavas != null) {
            injectJavaScript();
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "injectJavaScript, addJavascriptInterface.interfaceObj = " + interfaceObj + ", interfaceName = " + interfaceName);
            }
        }
    }

    @Override
    public void setWebViewClient(WebViewClient client) {
        if (client instanceof SafeWebViewClient) {
            if (mJsCallJavas != null) {
                super.setWebViewClient(client);
            } else {
                mWebViewClient = (SafeWebViewClient) client;
            }
        } else {
            super.setWebViewClient(client);
        }
    }

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        if (client instanceof SafeWebChromeClient) {
            if (mJsCallJavas != null) {
                super.setWebChromeClient(client);
            } else {
                mWebChromeClient = (SafeWebChromeClient) client;
            }
        } else {
            super.setWebChromeClient(client);
        }
    }

    @Override
    public void destroy() {
        if (mJsCallJavas != null) {
            mJsCallJavas.clear();
        }
        if (mInjectJavaScripts != null) {
            mInjectJavaScripts.clear();
        }
        removeAllViews();
        //WebView中包含一个ZoomButtonsController，当使用web.getSettings().setBuiltInZoomControls(true);启用该设置后，用户一旦触摸屏幕，就会出现缩放控制图标。这个图标过上几秒会自动消失，但在3.0系统以上上，如果图标自动消失前退出当前Activity的话，就会发生ZoomButton找不到依附的Window而造成程序崩溃，解决办法很简单就是在Activity的ondestory方法中调用web.setVisibility(View.GONE);方法，手动将其隐藏，就不会崩溃了。在3.0一下系统上不会出现该崩溃问题，真是各种崩溃，防不胜防啊！
        setVisibility(View.GONE);
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup mWebViewContainer = (ViewGroup) getParent();
            mWebViewContainer.removeAllViews();
        }
        releaseConfigCallback();
        super.destroy();
    }

    @Override
    public void loadUrl(String url) {
        loadUrl(url, null);
    }

    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        if (mJsCallJavas == null) {
            setClient();
        }
        super.loadUrl(url, additionalHttpHeaders);
    }

    private void setClient() {
        if (mWebChromeClient != null) {
            setWebChromeClient(mWebChromeClient);
            mWebChromeClient = null;
        }
        if (mWebViewClient != null) {
            setWebViewClient(mWebViewClient);
            mWebViewClient = null;
        }
    }

    /**
     * 添加并注入JavaScript脚本（和“addJavascriptInterface”注入对象的注入时机一致，100%能注入成功）；
     * 注意：为了做到能100%注入，需要在注入的js中自行判断对象是否已经存在（如：if (typeof(window.Android) = 'undefined')）；
     * @param javaScript
     */
    public void addInjectJavaScript(String javaScript) {
        if (mInjectJavaScripts == null) {
            mInjectJavaScripts = new HashMap<Integer, String>();
        }
        mInjectJavaScripts.put(javaScript.hashCode(), javaScript);
        injectExtraJavaScript();
    }

    private void injectJavaScript() {
        for (Map.Entry<String, JsCallJava> entry : mJsCallJavas.entrySet()) {
            loadUrl(entry.getValue().getPreloadInterfaceJS());
        }
    }

    private void injectExtraJavaScript() {
        for (Map.Entry<Integer, String> entry : mInjectJavaScripts.entrySet()) {
            loadUrl("javascript:" + entry.getValue());
        }
    }

    /**
     * 如果没有使用addJavascriptInterface方法，不需要使用这个类；
     */
    public class SafeWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (mJsCallJavas != null) {
                injectJavaScript();
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "injectJavaScript, onPageStarted.url = " + view.getUrl());
                }
            }
            if (mInjectJavaScripts != null) {
                injectExtraJavaScript();
            }
            super.onPageStarted(view, url, favicon);
        }
    }

    /**
     * 如果没有使用addJavascriptInterface方法，不需要使用这个类；
     */
    public class SafeWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (mJsCallJavas != null) {
                injectJavaScript();
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "injectJavaScript, onProgressChanged.newProgress = " + newProgress + ", url = " + view.getUrl());
                }
            }
            if (mInjectJavaScripts != null) {
                injectExtraJavaScript();
            }
            super.onProgressChanged(view, newProgress);
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            if (mJsCallJavas != null && JsCallJava.isSafeWebViewCallMsg(message)) {
                JSONObject jsonObject = JsCallJava.getMsgJSONObject(message);
                String interfacedName = JsCallJava.getInterfacedName(jsonObject);
                if (interfacedName != null) {
                    JsCallJava jsCallJava = mJsCallJavas.get(interfacedName);
                    if (jsCallJava != null) {
                        result.confirm(jsCallJava.call(view, jsonObject));
                    }
                }
                return true;
            } else {
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }
        }
    }

    // 解决WebView内存泄漏问题；
    private void releaseConfigCallback() {
        if (android.os.Build.VERSION.SDK_INT < 16) { // JELLY_BEAN
            try {
                Field field = WebView.class.getDeclaredField("mWebViewCore");
                field = field.getType().getDeclaredField("mBrowserFrame");
                field = field.getType().getDeclaredField("sConfigCallback");
                field.setAccessible(true);
                field.set(null, null);
            } catch (NoSuchFieldException e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            } catch (IllegalAccessException e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            }
        } else {
            try {
                Field sConfigCallback = Class.forName("android.webkit.BrowserFrame").getDeclaredField("sConfigCallback");
                if (sConfigCallback != null) {
                    sConfigCallback.setAccessible(true);
                    sConfigCallback.set(null, null);
                }
            } catch (NoSuchFieldException e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            } catch (ClassNotFoundException e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            } catch (IllegalAccessException e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Android 4.4 KitKat 使用Chrome DevTools 远程调试WebView
     * WebView.setWebContentsDebuggingEnabled(true);
     * http://blog.csdn.net/t12x3456/article/details/14225235
     */
    @TargetApi(19)
    protected void trySetWebDebuggEnabled() {
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 19) {
            try {
                Class<?> clazz = WebView.class;
                Method method = clazz.getMethod("setWebContentsDebuggingEnabled", boolean.class);
                method.invoke(null, true);
            } catch (Throwable e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 解决Webview远程执行代码漏洞，避免被“getClass”方法恶意利用（在loadUrl之前调用，如：MyWebView(Context context, AttributeSet attrs)里面）；
     * 漏洞详解：http://drops.wooyun.org/papers/548
     * <p/>
     * function execute(cmdArgs)
     * {
     *     for (var obj in window) {
     *        if ("getClass" in window[obj]) {
     *            alert(obj);
     *            return ?window[obj].getClass().forName("java.lang.Runtime")
     *                 .getMethod("getRuntime",null).invoke(null,null).exec(cmdArgs);
     *        }
     *     }
     * }
     *
     * @return
     */
    @TargetApi(11)
    protected boolean removeSearchBoxJavaBridge() {
        try {
            if (Build.VERSION.SDK_INT >= 11 && Build.VERSION.SDK_INT < 17) {
                Method method = this.getClass().getMethod("removeJavascriptInterface", String.class);
                method.invoke(this, "searchBoxJavaBridge_");
                return true;
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * 解决Android4.2中开启了辅助模式后，LocalActivityManager控制的Activity与AccessibilityInjector不兼容导致的崩溃问题；
     * Caused by: java.lang.NullPointerException
     * at android.webkit.AccessibilityInjector$TextToSpeechWrapper$1.onInit(AccessibilityInjector.java:753)
     * ...
     * at android.webkit.WebSettingsClassic.setJavaScriptEnabled(WebSettingsClassic.java:1125)
     * 必须放在webSettings.setJavaScriptEnabled之前执行；
     */
    protected void fixedAccessibilityInjectorException() {
        if (Build.VERSION.SDK_INT == 17) {
            try {
                Object webViewProvider = WebView.class.getMethod("getWebViewProvider").invoke(this);
                Method getAccessibilityInjector = webViewProvider.getClass().getDeclaredMethod("getAccessibilityInjector");
                getAccessibilityInjector.setAccessible(true);
                Object accessibilityInjector = getAccessibilityInjector.invoke(webViewProvider);
                getAccessibilityInjector.setAccessible(false);
                Field mAccessibilityManagerField = accessibilityInjector.getClass().getDeclaredField("mAccessibilityManager");
                mAccessibilityManagerField.setAccessible(true);
                Object mAccessibilityManager = mAccessibilityManagerField.get(accessibilityInjector);
                mAccessibilityManagerField.setAccessible(false);
                Field mIsEnabledField = mAccessibilityManager.getClass().getDeclaredField("mIsEnabled");
                mIsEnabledField.setAccessible(true);
                mIsEnabledField.set(mAccessibilityManager, false);
                mIsEnabledField.setAccessible(false);
            } catch (Exception e) {
                if (BuildConfig.DEBUG) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 向网页更新Cookie，设置cookie后不需要页面刷新即可生效；
     */
    protected void updateCookies(String url, String value) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1) { // 2.3及以下
            CookieSyncManager.createInstance(getContext().getApplicationContext());
        }
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setCookie(url, value);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1) { // 2.3及以下
            CookieSyncManager.getInstance().sync();
        }
    }
}