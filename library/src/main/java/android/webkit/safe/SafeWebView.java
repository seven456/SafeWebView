package android.webkit.safe;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Created by zhangguojun on 2015/6/21.
 */
public class SafeWebView extends WebView {
    private Object mInterfaceObj;
    private String mInterfaceName;

    public SafeWebView(Context context) {
        this(context, null);
    }

    public SafeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        removeSearchBoxJavaBridge();
    }

    /**
     * 虽然android4.2.2及以上版本的addJavascriptInterface方法解决了安全问题，但为了支持“网页能将JS函数传到Java层”功能，统一采用本地JS注入的方式解决Bridge问题；
     */
    @Override
    public void addJavascriptInterface(Object interfaceObj, String interfaceName) {
        mInterfaceObj = interfaceObj;
        mInterfaceName = interfaceName;
    }

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        if (client instanceof SafeWebChromeClient) {
            ((SafeWebChromeClient) client).addJavascriptInterface(mInterfaceObj, mInterfaceName);
        }
        super.setWebChromeClient(client);
    }

    @Override
    public void destroy() {
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
     * <p/>
     * WebView.setWebContentsDebuggingEnabled(true);
     * http://blog.csdn.net/t12x3456/article/details/14225235
     */
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
     *
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
     * @return
     */
    @TargetApi(11)
    protected boolean removeSearchBoxJavaBridge() {
        try {
            if (Build.VERSION.SDK_INT >= 11 && Build.VERSION.SDK_INT < 17) {
                Method method =  this.getClass().getMethod("removeJavascriptInterface", String.class);
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