package android.webkit.safe;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.AndroidRuntimeException;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.JsPromptResult;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.apache.http.client.utils.URLEncodedUtils;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhangguojun on 2015/6/21.
 *
 * Android4.2.2以下版本WebView有远程执行代码漏洞
 * 乌云上的介绍：http://www.wooyun.org/bugs/wooyun-2010-067676
 * 测试方法：让自己的WebView加载http://drops.wooyun.org/webview.html
 */
public class SafeWebView extends WebView {
    private static final String TAG = "SafeWebView";
    private Map<String, JsCallJava> mJsCallJavas;
    private Map<String, String> mInjectJavaScripts;
    private FixedOnReceivedTitle mFixedOnReceivedTitle;
    private boolean mIsInited;
    private Boolean mIsAccessibilityEnabledOriginal;

    public SafeWebView(Context context) {
        this(context, null);
    }

    public SafeWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        removeSearchBoxJavaBridge();

        // WebView跨源（加载本地文件）攻击分析：http://blogs.360.cn/360mobile/2014/09/22/webview%E8%B7%A8%E6%BA%90%E6%94%BB%E5%87%BB%E5%88%86%E6%9E%90/
        // 是否允许WebView使用File协议，移动版的Chrome默认禁止加载file协议的文件；
        getSettings().setAllowFileAccess(false);

        mFixedOnReceivedTitle = new FixedOnReceivedTitle();
        mIsInited = true;
    }

    /**
     * 经过大量的测试，按照以下方式才能保证JS脚本100%注入成功：
     * 1、在第一次loadUrl之前注入JS（在addJavascriptInterface里面注入即可，setWebViewClient和setWebChromeClient要在addJavascriptInterface之前执行）；
     * 2、在webViewClient.onPageStarted中都注入JS；
     * 3、在webChromeClient.onProgressChanged中都注入JS，并且不能通过自检查（onJsPrompt里面判断）JS是否注入成功来减少注入JS的次数，因为网页中的JS可以同时打开多个url导致无法控制检查的准确性；
     * 4、注入的JS中已经在脚本（./library/doc/notRepeat.js）中检查注入的对象是否已经存在，避免注入对象被重新赋值导致网页引用该对象的方法时发生异常；
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
        injectJavaScript();
        if (LogUtils.isDebug()) {
            Log.d(TAG, "injectJavaScript, addJavascriptInterface.interfaceObj = " + interfaceObj + ", interfaceName = " + interfaceName);
        }
    }

    @Override
    public void setWebChromeClient(WebChromeClient client) {
        mFixedOnReceivedTitle.setWebChromeClient(client);
        super.setWebChromeClient(client);
    }

    @Override
    public void destroy() {
        if (mJsCallJavas != null) {
            mJsCallJavas.clear();
        }
        if (mInjectJavaScripts != null) {
            mInjectJavaScripts.clear();
        }
        removeAllViewsInLayout();
        fixedStillAttached();
        releaseConfigCallback();
        if (mIsInited) {
            resetAccessibilityEnabled();
//            java.lang.RuntimeException: Unable to start activity ComponentInfo{com.xxx.xxx/com.xxx.accounts.ui.a.WebViewActivity}: java.lang.NullPointerException: Attempt to invoke interface method 'void android.webkit.WebViewProvider.destroy()' on a null object reference
//            at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2460)
//            at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2522)
//            at android.app.ActivityThread.access$800(ActivityThread.java:167)
//            at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1417)
//            at android.os.Handler.dispatchMessage(Handler.java:111)
//            at android.os.Looper.loop(Looper.java:194)
//            at android.app.ActivityThread.main(ActivityThread.java:5537)
//            at java.lang.reflect.Method.invoke(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:372)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:955)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:750)
//            Caused by: java.lang.NullPointerException: Attempt to invoke interface method 'void android.webkit.WebViewProvider.destroy()' on a null object reference
//            at android.webkit.WebView.destroy(WebView.java:734)
//            at android.webkit.safe.SafeWebView.destroy(XXX:93)
//            at android.webkit.safe.SafeWebView.setOverScrollMode(XXX:214)
//            at android.view.View.<init>(View.java:3626)
//            at android.view.View.<init>(View.java:3730)
//            at android.view.ViewGroup.<init
            super.destroy();
        }
    }

    @Override
    public void clearHistory() {
        if (mIsInited) {
//            java.lang.RuntimeException: Unable to resume activity {com.xxx.xxx/com.xxx.xxx.webview.WebViewActivity}: java.lang.NullPointerException: Attempt to invoke interface method 'void android.webkit.WebViewProvider.clearHistory()' on a null object reference
//            at android.app.ActivityThread.performResumeActivity(ActivityThread.java:3394)
//            at android.app.ActivityThread.handleResumeActivity(ActivityThread.java:3425)
//            at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2763)
//            at android.app.ActivityThread.access$900(ActivityThread.java:177)
//            at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1448)
//            at android.os.Handler.dispatchMessage(Handler.java:102)
//            at android.os.Looper.loop(Looper.java:145)
//            at android.app.ActivityThread.main(ActivityThread.java:5942)
//            at java.lang.reflect.Method.invoke(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:372)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:1400)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1195)
//            Caused by: java.lang.NullPointerException: Attempt to invoke interface method 'void android.webkit.WebViewProvider.clearHistory()' on a null object reference
//            at android.webkit.WebView.clearHistory(WebView.java:1483)
//            at com.xxx.xxx.webview.XXXWebView.clear(XXX:281)
//            at com.xxx.xxx.webview.XXXWebView.destroy(XXX:189)
//            at android.webkit.safe.SafeWebView.setOverScrollMode(XXX:214)
//            at android.view.View.<init>(View.java:3788)
//            at android.view.View.<init>(View.java:3902)
//            at android.view.ViewGroup.<init
            super.clearHistory();
        }
    }

    public static Pair<Boolean, String> isWebViewPackageException(Throwable e) {
        String messageCause = e.getCause() == null ? e.toString() : e.getCause().toString();
        String trace = Log.getStackTraceString(e);
        if (trace.contains("android.content.pm.PackageManager$NameNotFoundException")
                || trace.contains("java.lang.RuntimeException: Cannot load WebView")
                || trace.contains("android.webkit.WebViewFactory$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed")) {
//            java.lang.RuntimeException: Unable to start activity ComponentInfo {com.xxx.xxx/com.xxx.xxx.zhaoyaojing.FAQsActivity}: android.util.AndroidRuntimeException: android.content.pm.PackageManager$NameNotFoundException: com.android.webview
//            at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2567)
//            at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2647)
//            at android.app.ActivityThread.access$800(ActivityThread.java:193)
//            at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1485)
//            at android.os.Handler.dispatchMessage(Handler.java:111)
//            at android.os.Looper.loop(Looper.java:194)
//            at android.app.ActivityThread.main(ActivityThread.java:5759)
//            at java.lang.reflect.Method.invoke(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:372)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:1040)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:835)
//            Caused by: android.util.AndroidRuntimeException: android.content.pm.PackageManager$NameNotFoundException: com.android.webview
//            at android.webkit.WebViewFactory.getFactoryClass(WebViewFactory.java:174)
//            at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:109)
//            at android.webkit.WebView.getFactory(WebView.java:2348)
//            at android.webkit.WebView.ensureProviderCreated(WebView.java:2343)
//            at android.webkit.WebView.setOverScrollMode(WebView.java:2402)
//            at android.view.View.<init>(View.java:3632)
//            at android.view.View.<init>(View.java:3736)
//            at android.view.ViewGroup.<init>(ViewGroup.java:524)
//            at android.widget.AbsoluteLayout.<init>(AbsoluteLayout.java:55)
//            at android.webkit.WebView.<init>(WebView.java:580)
//            at android.webkit.WebView.<init>(WebView.java:525)
//            at android.webkit.WebView.<init>(WebView.java:508)
//            at android.webkit.WebView.<init>(WebView.java:495)
//            at android.webkit.safe.SafeWebView.<init>(XXX:43)
//            at android.webkit.safe.SafeWebView.<init>(XXX:39)
//            at com.xxx.xxx.zhaoyaojing.FAQsActivity.a(XXX:37)
//            at com.xxx.xxx.zhaoyaojing.FAQsActivity.onCreate(XXX:31)
//            at android.app.Activity.performCreate(Activity.java:6131)
//            at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1112)
//            at com.morgoo.droidplugin.c.b.ht.callActivityOnCreate(XXX:110)
//            at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2514)
//            ... 10 more
//            Caused by: android.content.pm.PackageManager$NameNotFoundException: com.android.webview
//            at android.app.ApplicationPackageManager.getPackageInfo(ApplicationPackageManager.java:125)
//            at android.webkit.WebViewFactory.getFactoryClass(WebViewFactory.java:146)
//            Failed to list WebView package libraries for loadNativeLibrary
//            android.content.pm.PackageManager$NameNotFoundException: com.android.webview
//            at android.app.ApplicationPackageManager.getApplicationInfo(ApplicationPackageManager.java:292)
//            at android.webkit.WebViewFactory.getWebViewNativeLibraryPaths(WebViewFactory.java:282)
//            at android.webkit.WebViewFactory.loadNativeLibrary(WebViewFactory.java:397)
//            at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:103)
//            at android.webkit.WebView.getFactory(WebView.java:2348)
//            at android.webkit.WebView.ensureProviderCreated(WebView.java:2343)
//            at android.webkit.WebView.setOverScrollMode(WebView.java:2402)
//            at android.view.View.<init>(View.java:3632)
//            at android.view.View.<init>(View.java:3736)
//            at android.view.ViewGroup.<init>(ViewGroup.java:524)
//            at android.widget.AbsoluteLayout.<init>(AbsoluteLayout.java:55)
//            at android.webkit.WebView.<init>(WebView.java:580)
//            at android.webkit.WebView.<init>(WebView.java:525)
//            at android.webkit.WebView.<init>(WebView.java:508)
//            at android.webkit.WebView.<init>(WebView.java:495)
//            at android.webkit.safe.SafeWebView.<init>(XXX:43)
//            at android.webkit.safe.SafeWebView.<init>(XXX:39)
//            at com.xxx.xxx.zhaoyaojing.FAQsActivity.a(XXX:37)
//            at com.xxx.xxx.zhaoyaojing.FAQsActivity.onCreate(XXX:31)
//            at android.app.Activity.performCreate(Activity.java:6131)
//            at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1112)
//            at com.morgoo.droidplugin.c.b.ht.callActivityOnCreate(XXX:110)
//            at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2514)
//            at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2647)
//            at android.app.ActivityThread.access$800(ActivityThread.java:193)
//            at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1485)
//            at android.os.Handler.dispatchMessage(Handler.java:111)
//            at android.os.Looper.loop(Looper.java:194)
//            at android.app.ActivityThread.main(ActivityThread.java:5759)
//            at java.lang.reflect.Method.invoke(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:372)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:1040)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:835)

//            Chromium WebView package does not exist
//            android.content.pm.PackageManager$NameNotFoundException: com.android.webview
//            at android.app.ApplicationPackageManager.getPackageInfo(ApplicationPackageManager.java:125)
//            at android.webkit.WebViewFactory.getFactoryClass(WebViewFactory.java:146)
//            at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:109)
//            at android.webkit.WebView.getFactory(WebView.java:2348)
//            at android.webkit.WebView.ensureProviderCreated(WebView.java:2343)
//            at android.webkit.WebView.setOverScrollMode(WebView.java:2402)
//            at android.view.View.<init>(View.java:3632)
//            at android.view.View.<init>(View.java:3736)
//            at android.view.ViewGroup.<init>(ViewGroup.java:524)
//            at android.widget.AbsoluteLayout.<init>(AbsoluteLayout.java:55)
//            at android.webkit.WebView.<init>(WebView.java:580)
//            at android.webkit.WebView.<init>(WebView.java:525)
//            at android.webkit.WebView.<init>(WebView.java:508)
//            at android.webkit.WebView.<init>(WebView.java:495)
//            at android.webkit.safe.SafeWebView.<init>(XXX:43)
//            at android.webkit.safe.SafeWebView.<init>(XXX:39)
//            at com.xxx.xxx.zhaoyaojing.FAQsActivity.a(XXX:37)
//            at com.xxx.xxx.zhaoyaojing.FAQsActivity.onCreate(XXX:31)
//            at android.app.Activity.performCreate(Activity.java:6131)
//            at android.app.Instrumentation.callActivityOnCreate(Instrumentation.java:1112)
//            at com.morgoo.droidplugin.c.b.ht.callActivityOnCreate(XXX:110)
//            at android.app.ActivityThread.performLaunchActivity(ActivityThread.java:2514)
//            at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2647)
//            at android.app.ActivityThread.access$800(ActivityThread.java:193)
//            at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1485)
//            at android.os.Handler.dispatchMessage(Handler.java:111)
//            at android.os.Looper.loop(Looper.java:194)
//            at android.app.ActivityThread.main(ActivityThread.java:5759)
//            at java.lang.reflect.Method.invoke(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:372)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:1040)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:835)

//            java.lang.RuntimeException: Unable to resume activity {com.xxx.xxx/com.xxx.xxx.webview.WebViewActivity}: android.util.AndroidRuntimeException: java.lang.RuntimeException: Cannot load WebView
//            at android.app.ActivityThread.performResumeActivity(ActivityThread.java:2989)
//            at android.app.ActivityThread.handleResumeActivity(ActivityThread.java:3018)
//            at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2422)
//            at android.app.ActivityThread.access$800(ActivityThread.java:154)
//            at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1348)
//            at android.os.Handler.dispatchMessage(Handler.java:110)
//            at android.os.Looper.loop(Looper.java:193)
//            at android.app.ActivityThread.main(ActivityThread.java:5328)
//            at java.lang.reflect.Method.invokeNative(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:515)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:835)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:651)
//            at dalvik.system.NativeStart.main(Native Method)
//            Caused by: android.util.AndroidRuntimeException: java.lang.RuntimeException: Cannot load WebView
//            at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:107)
//            at android.webkit.WebView.getFactory(WebView.java:2087)
//            at android.webkit.WebView.ensureProviderCreated(WebView.java:2082)
//            at android.webkit.WebView.setOverScrollMode(WebView.java:2167)
//            at android.webkit.safe.SafeWebView.setOverScrollMode(XXX:157)
//            at android.view.View.<init>(View.java:3478)
//            at android.view.View.<init>(View.java:3534)
//            at android.view.ViewGroup.<init>(ViewGroup.java:494)
//            at android.widget.AbsoluteLayout.<init>(AbsoluteLayout.java:52)
//            at android.webkit.WebView.<init>(WebView.java:525)
//            at android.webkit.WebView.<init>(WebView.java:502)
//            at android.webkit.WebView.<init>(WebView.java:482)
//            at android.webkit.WebView.<init>(WebView.java:471)
//            at android.webkit.safe.SafeWebView.<init>(XXX:51)
//            at com.xxx.xxx.webview.XXXWebView.<init>(XXX:93)
//            at com.xxx.xxx.webview.WebViewFragment.initWebView(XXX:128)
//            at com.xxx.xxx.webview.WebViewFragment.init(XXX:119)
//            at com.xxx.xxx.webview.WebViewFragment.onResume(XXX:362)
//            at android.support.v4.app.Fragment.performResume(XXX:1832)
//            at android.support.v4.app.FragmentManagerImpl.moveToState(XXX:995)
//            at android.support.v4.app.FragmentManagerImpl.moveToState(XXX:1138)
//            at android.support.v4.app.FragmentManagerImpl.moveToState(XXX:1120)
//            at android.support.v4.app.FragmentManagerImpl.dispatchResume(XXX:1939)
//            at android.support.v4.app.FragmentActivity.onResumeFragments(XXX:447)
//            at android.support.v4.app.FragmentActivity.onPostResume(XXX:436)
//            at android.app.Activity.performResume(Activity.java:5446)
//            at android.app.ActivityThread.performResumeActivity(ActivityThread.java:2975)
//            ... 12 more
//            Caused by: java.lang.RuntimeException: Cannot load WebView
//            at com.android.org.chromium.android_webview.AwBrowserProcess.loadLibrary(AwBrowserProcess.java:32)
//            at com.android.webview.chromium.WebViewChromiumFactoryProvider.<init>(WebViewChromiumFactoryProvider.java:82)
//            at java.lang.Class.newInstanceImpl(Native Method)
//            at java.lang.Class.newInstance(Class.java:1215)
//            at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:102)
//            ... 38 more
//            Caused by: com.android.org.chromium.content.common.ProcessInitException
//            at com.android.org.chromium.content.app.LibraryLoader.loadAlreadyLocked(LibraryLoader.java:111)
//            at com.android.org.chromium.content.app.LibraryLoader.loadNow(LibraryLoader.java:79)
//            at com.android.org.chromium.android_webview.AwBrowserProcess.loadLibrary(AwBrowserProcess.java:30)
//            ... 42 more
//            Caused by: java.lang.UnsatisfiedLinkError: dlopen failed: unknown reloc type 31 @ 0x7d570800 (139610)
//            at java.lang.Runtime.loadLibrary(Runtime.java:393)
//            at java.lang.System.loadLibrary(System.java:606)
//            at com.android.org.chromium.content.app.LibraryLoader.loadAlreadyLocked(LibraryLoader.java:105)
//            .. 44 more

//            java.lang.RuntimeException: Unable to resume activity {com.xxx.xxx/com.xxx.xxx.webview.WebViewActivity}: android.util.AndroidRuntimeException: android.webkit.WebViewFactory$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed
//            at android.app.ActivityThread.performResumeActivity(ActivityThread.java:3445)
//            at android.app.ActivityThread.handleResumeActivity(ActivityThread.java:3485)
//            at android.app.ActivityThread.handleLaunchActivity(ActivityThread.java:2743)
//            at android.app.ActivityThread.-wrap12(ActivityThread.java)
//            at android.app.ActivityThread$H.handleMessage(ActivityThread.java:1475)
//            at android.os.Handler.dispatchMessage(Handler.java:102)
//            at android.os.Looper.loop(Looper.java:154)
//            at android.app.ActivityThread.main(ActivityThread.java:6147)
//            at java.lang.reflect.Method.invoke(Native Method)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:887)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:777)
//            Caused by: android.util.AndroidRuntimeException: android.webkit.WebViewFactory$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed
//            at android.webkit.WebViewFactory.getProviderClass(WebViewFactory.java:371)
//            at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:194)
//            at android.webkit.WebView.getFactory(WebView.java:2325)
//            at android.webkit.WebView.ensureProviderCreated(WebView.java:2320)
//            at android.webkit.WebView.<init>(WebView.java:635)
//            at android.webkit.WebView.<init>(WebView.java:572)
//            at android.webkit.WebView.<init>(WebView.java:555)
//            at android.webkit.WebView.<init>(WebView.java:542)
//            at android.webkit.safe.SafeWebView.<init>(XXX:51)
//            at com.xxx.xxx.webview.XXXWebView.<init>(XXX:96)
//            at com.xxx.xxx.webview.WebViewFragment.R(XXX:123)
//            at com.xxx.xxx.webview.WebViewFragment.Q(XXX:114)
//            at com.xxx.xxx.webview.WebViewFragment.r(XXX:422)
//            at android.support.v4.app.Fragment.G(XXX:1832)
//            at android.support.v4.app.w.a(XXX:995)
//            at android.support.v4.app.w.a(XXX:1138)
//            at android.support.v4.app.w.a(XXX:1120)
//            at android.support.v4.app.w.l(XXX:1939)
//            at android.support.v4.app.FragmentActivity.onResumeFragments(XXX:447)
//            at android.support.v4.app.FragmentActivity.onPostResume(XXX:436)
//            at android.app.Activity.performResume(Activity.java:6833)
//            at android.app.ActivityThread.performResumeActivity(ActivityThread.java:3422)
//            ... 10 more
//            Caused by: android.webkit.WebViewFactory$MissingWebViewPackageException: Failed to load WebView provider: No WebView installed
//            at android.webkit.WebViewFactory.getWebViewContextAndSetProvider(WebViewFactory.java:270)
//            at android.webkit.WebViewFactory.getProviderClass(WebViewFactory.java:330)
//            ... 31 more

            LogUtils.safeCheckCrash(TAG, "isWebViewPackageException", e);
            return new Pair<Boolean, String>(true, "WebView load failed, " + messageCause);
        }
        return new Pair<Boolean, String>(false, messageCause);
    }

    @Override
    public void setOverScrollMode(int mode) {
        try {
            super.setOverScrollMode(mode);
        } catch (Throwable e) {
            Pair<Boolean, String> pair = isWebViewPackageException(e);
            if (pair.first) {
                Toast.makeText(getContext(), pair.second, Toast.LENGTH_SHORT).show();
                destroy();
            } else {
                throw e;
            }
        }
    }

    @Override
    public boolean isPrivateBrowsingEnabled() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
                && getSettings() == null) {
//            java.lang.NullPointerException
//            at android.webkit.WebView.isPrivateBrowsingEnabled(WebView.java:2458)
//            at android.webkit.HTML5Audio$IsPrivateBrowsingEnabledGetter$1.run(HTML5Audio.java:105)
//            at android.os.Handler.handleCallback(Handler.java:605)
//            at android.os.Handler.dispatchMessage(Handler.java:92)
//            at android.os.Looper.loop(Looper.java:137)
//            at android.app.ActivityThread.main(ActivityThread.java:4456)
//            at java.lang.reflect.Method.invokeNative(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:511)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:787)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:554)
//            at dalvik.system.NativeStart.main(Native Method)
            return false; // getSettings().isPrivateBrowsingEnabled()
        } else {
            return super.isPrivateBrowsingEnabled();
        }
    }

    /**
     * 添加并注入JavaScript脚本（和“addJavascriptInterface”注入对象的注入时机一致，100%能注入成功）；
     * 注意：为了做到能100%注入，需要在注入的js中自行判断对象是否已经存在（如：if (typeof(window.Android) = 'undefined')）；
     * @param javaScript
     */
    public void addInjectJavaScript(String javaScript) {
        if (mInjectJavaScripts == null) {
            mInjectJavaScripts = new HashMap<String, String>();
        }
        mInjectJavaScripts.put(String.valueOf(javaScript.hashCode()), javaScript);
        injectExtraJavaScript();
    }

    private void injectJavaScript() {
        for (Map.Entry<String, JsCallJava> entry : mJsCallJavas.entrySet()) {
            this.loadUrl(buildNotRepeatInjectJS(entry.getKey(), entry.getValue().getPreloadInterfaceJS()));
        }
    }

    private void injectExtraJavaScript() {
        for (Map.Entry<String, String> entry : mInjectJavaScripts.entrySet()) {
            this.loadUrl(buildNotRepeatInjectJS(entry.getKey(), entry.getValue()));
        }
    }

    /**
     * 构建一个“不会重复注入”的js脚本；
     * @param key
     * @param js
     * @return
     */
    public String buildNotRepeatInjectJS(String key, String js) {
        String obj = String.format("__injectFlag_%1$s__", key);
        StringBuilder sb = new StringBuilder();
        sb.append("javascript:try{(function(){if(window.");
        sb.append(obj);
        sb.append("){console.log('");
        sb.append(obj);
        sb.append(" has been injected');return;}window.");
        sb.append(obj);
        sb.append("=true;");
        sb.append(js);
        sb.append("}())}catch(e){console.warn(e)}");
        return sb.toString();
    }

    /**
     * 构建一个“带try catch”的js脚本；
     * @param js
     * @return
     */
    public String buildTryCatchInjectJS(String js) {
        StringBuilder sb = new StringBuilder();
        sb.append("javascript:try{");
        sb.append(js);
        sb.append("}catch(e){console.warn(e)}");
        return sb.toString();
    }

    /**
     * 如果没有使用addJavascriptInterface方法，不需要使用这个类；
     */
    public class SafeWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (mJsCallJavas != null) {
                injectJavaScript();
                if (LogUtils.isDebug()) {
                    Log.d(TAG, "injectJavaScript, onPageStarted.url = " + view.getUrl());
                }
            }
            if (mInjectJavaScripts != null) {
                injectExtraJavaScript();
            }
            mFixedOnReceivedTitle.onPageStarted();
            fixedAccessibilityInjectorExceptionForOnPageFinished(url);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mFixedOnReceivedTitle.onPageFinished(view);
            if (LogUtils.isDebug()) {
                Log.d(TAG, "onPageFinished.url = " + view.getUrl());
            }
            super.onPageFinished(view, url);
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
                if (LogUtils.isDebug()) {
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

        @Override
        public void onReceivedTitle(WebView view, String title) {
            mFixedOnReceivedTitle.onReceivedTitle();
            super.onReceivedTitle(view, title);
        }
    }

    /**
     * 解决部分手机webView返回时不触发onReceivedTitle的问题（如：三星SM-G9008V 4.4.2）；
     */
    private static class FixedOnReceivedTitle {
        private WebChromeClient mWebChromeClient;
        private boolean mIsOnReceivedTitle;

        public void setWebChromeClient(WebChromeClient webChromeClient) {
            mWebChromeClient = webChromeClient;
        }

        public void onPageStarted() {
            mIsOnReceivedTitle = false;
        }

        public void onPageFinished(WebView view) {
            if (!mIsOnReceivedTitle && mWebChromeClient != null) {
//                Samsung A8000 Android5.1.1
//                java.lang.NullPointerException: Attempt to invoke virtual method 'int org.chromium.content_public.browser.NavigationHistory.getCurrentEntryIndex()' on a null object reference
//                at com.android.webview.chromium.WebBackForwardListChromium.<init>(WebBackForwardListChromium.java:28)
//                at com.android.webview.chromium.WebViewChromium.copyBackForwardList(WebViewChromium.java:1103)
//                at android.webkit.WebView.copyBackForwardList(WebView.java:1533)
//                at android.webkit.safe.SafeWebView$FixedOnReceivedTitle.onPageFinished(XXX:295)
//                at android.webkit.safe.SafeWebView$SafeWebViewClient.onPageFinished(XXX:227)
//                at com.xxx.xxx.webview.XXXWebView$InnerWebViewClient.onPageFinished(XXX:372)
//                at com.android.webview.chromium.WebViewContentsClientAdapter.onPageFinished(WebViewContentsClientAdapter.java:498)
//                at org.chromium.android_webview.AwContentsClientCallbackHelper$MyHandler.handleMessage(AwContentsClientCallbackHelper.java:163)
//                at android.os.Handler.dispatchMessage(Handler.java:102)
//                at android.os.Looper.loop(Looper.java:145)
//                at android.app.ActivityThread.main(ActivityThread.java:6963)
//                at java.lang.reflect.Method.invoke(Native Method)
//                at java.lang.reflect.Method.invoke(Method.java:372)
//                at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:1404)
//                at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1199)
                WebBackForwardList list = null;
                try {
                    list = view.copyBackForwardList();
                } catch (NullPointerException e) {
                    if (LogUtils.isDebug()) {
                        e.printStackTrace();
                    }
                }
                if (list != null
                        && list.getSize() > 0
                        && list.getCurrentIndex() >= 0
                        && list.getItemAtIndex(list.getCurrentIndex()) != null) {
                    String previousTitle = list.getItemAtIndex(list.getCurrentIndex()).getTitle();
                    mWebChromeClient.onReceivedTitle(view, previousTitle);
                }
            }
        }

        public void onReceivedTitle() {
            mIsOnReceivedTitle = true;
        }
    }

    // Activity在onDestory时调用webView的destroy，可以停止播放页面中的音频
    private void fixedStillAttached() {
        // java.lang.Throwable: Error: WebView.destroy() called while still attached!
        // at android.webkit.WebViewClassic.destroy(WebViewClassic.java:4142)
        // at android.webkit.WebView.destroy(WebView.java:707)
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) { // 由于自定义webView构建时传入了该Activity的context对象，因此需要先从父容器中移除webView，然后再销毁webView；
            ViewGroup mWebViewContainer = (ViewGroup) getParent();
            mWebViewContainer.removeAllViewsInLayout();
        }
    }

    // 解决WebView内存泄漏问题；
    private void releaseConfigCallback() {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) { // JELLY_BEAN
            try {
                Field field = WebView.class.getDeclaredField("mWebViewCore");
                field = field.getType().getDeclaredField("mBrowserFrame");
                field = field.getType().getDeclaredField("sConfigCallback");
                field.setAccessible(true);
                field.set(null, null);
            } catch (NoSuchFieldException e) {
                if (LogUtils.isDebug()) {
                    e.printStackTrace();
                }
            } catch (IllegalAccessException e) {
                if (LogUtils.isDebug()) {
                    e.printStackTrace();
                }
            }
        } else if(android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)  { // KITKAT
            try {
                Field sConfigCallback = Class.forName("android.webkit.BrowserFrame").getDeclaredField("sConfigCallback");
                if (sConfigCallback != null) {
                    sConfigCallback.setAccessible(true);
                    sConfigCallback.set(null, null);
                }
            } catch (NoSuchFieldException e) {
                if (LogUtils.isDebug()) {
                    e.printStackTrace();
                }
            } catch (ClassNotFoundException e) {
                if (LogUtils.isDebug()) {
                    e.printStackTrace();
                }
            } catch (IllegalAccessException e) {
                if (LogUtils.isDebug()) {
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
        if (LogUtils.isDebug() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                Class<?> clazz = WebView.class;
                Method method = clazz.getMethod("setWebContentsDebuggingEnabled", boolean.class);
                method.invoke(null, true);
            } catch (Throwable e) {
                if (LogUtils.isDebug()) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Method method = this.getClass().getMethod("removeJavascriptInterface", String.class);
                method.invoke(this, "searchBoxJavaBridge_");
                return true;
            }
        } catch (Exception e) {
            if (LogUtils.isDebug()) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * 解决部分Android4.2中开启了辅助模式后，“android.webkit.AccessibilityInjector$TextToSpeechWrapper$1.onInit中synchronized(mTextToSpeech)”空指针导致的崩溃问题；
     * 必放在 {@link WebSettings#setJavaScriptEnabled }之前执行；
     *
     * https://code.google.com/p/android/issues/detail?id=40944
     *
     * 如：
     * Xiaomi HM NOTE 1TD Android4.2.2
     * Samsung GT-I9500 Android4.2.2
     * ZTE V967S Android4.2.1
     * Lenovo A850 Android4.2.2
     * HUAWEI Y518-T00 Android4.2.2
     * Huawei G610-T00 Android4.2.1
     * Huawei U9508 Android4.2.2
     * OPPO R829T Android4.2.2
     *
     * 一、android.webkit.WebSettingsClassic.setJavaScriptEnabled时崩溃；
     * java.lang.NullPointerException
     * at android.webkit.AccessibilityInjector$TextToSpeechWrapper$1.onInit(AccessibilityInjector.java:753)
     * at android.speech.tts.TextToSpeech.dispatchOnInit(TextToSpeech.java:653)
     * at android.speech.tts.TextToSpeech.initTts(TextToSpeech.java:632)
     * at android.speech.tts.TextToSpeech.<init>(TextToSpeech.java:553)
     * at android.webkit.AccessibilityInjector$TextToSpeechWrapper.<init>(AccessibilityInjector.java:676)
     * at android.webkit.AccessibilityInjector.addTtsApis(AccessibilityInjector.java:480)
     * at android.webkit.AccessibilityInjector.addAccessibilityApisIfNecessary(AccessibilityInjector.java:168)
     * at android.webkit.AccessibilityInjector.updateJavaScriptEnabled(AccessibilityInjector.java:415)
     * at android.webkit.WebViewClassic.updateJavaScriptEnabled(WebViewClassic.java:2017)
     * at android.webkit.WebSettingsClassic.setJavaScriptEnabled(WebSettingsClassic.java:1214)
     *
     * 二、android.webkit.WebViewClassic.onPageStarted时崩溃；
     * java.lang.NullPointerException
     * at android.webkit.AccessibilityInjector$TextToSpeechWrapper$1.onInit(AccessibilityInjector.java:753)
     * at android.speech.tts.TextToSpeech.dispatchOnInit(TextToSpeech.java:640)
     * at android.speech.tts.TextToSpeech.initTts(TextToSpeech.java:619)
     * at android.speech.tts.TextToSpeech.<init>(TextToSpeech.java:553)
     * at android.webkit.AccessibilityInjector$TextToSpeechWrapper.<init>(AccessibilityInjector.java:676)
     * at android.webkit.AccessibilityInjector.addTtsApis(AccessibilityInjector.java:480)
     * at android.webkit.AccessibilityInjector.addAccessibilityApisIfNecessary(AccessibilityInjector.java:168)
     * at android.webkit.AccessibilityInjector.onPageStarted(AccessibilityInjector.java:340)
     * at android.webkit.WebViewClassic.onPageStarted(WebViewClassic.java:3980)
     * at android.webkit.CallbackProxy.handleMessage(CallbackProxy.java:312)
     * at android.os.Handler.dispatchMessage(Handler.java:99)
     * at android.os.Looper.loop(Looper.java:137)
     * at android.app.ActivityThread.main(ActivityThread.java:5102)
     * at java.lang.reflect.Method.invokeNative(Native Method)
     * at java.lang.reflect.Method.invoke(Method.java:511)
     * at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:797)
     * at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:564)
     * at dalvik.system.NativeStart.main(Native Method)
     *
     * 由于第二个崩溃无法干预，在WebView初始化时直接关闭AccessibilityInjector；
     */
    protected void fixedAccessibilityInjectorException() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN_MR1
                && mIsAccessibilityEnabledOriginal == null
                && isAccessibilityEnabled()) {
            mIsAccessibilityEnabledOriginal = true;
            setAccessibilityEnabled(false);
        }
    }

    /**
     * 解决部分Android4.1中开启了辅助模式后，url参数不合法导致的崩溃问题；
     * url参数分隔符不使用“&”而是用“;”时，如：http://m.heise.de/newsticker/meldung/TomTom-baut-um-1643641.html?mrw_channel=ho;mrw_channel=ho;from-classic=1
     *
     * 参考：
     * https://code.google.com/p/android/issues/detail?id=35100
     * http://osdir.com/ml/Android-Developers/2012-07/msg02123.html
     *
     * 如：
     * Huawei HUAWEI C8815 4.1.2(16)
     * ZTE ZTE N919 4.1.2(16)
     * Coolpad 8190Q 4.1.2(16)
     * Lenovo Lenovo A706 4.1.2(16)
     * Xiaomi MI 2 4.1.1(16)
     *
     * java.lang.IllegalArgumentException: bad parameter
     * at org.apache.http.client.utils.URLEncodedUtils.parse(URLEncodedUtils.java:139)
     * at org.apache.http.client.utils.URLEncodedUtils.parse(URLEncodedUtils.java:76)
     * at android.webkit.AccessibilityInjector.getAxsUrlParameterValue(AccessibilityInjector.java:406)
     * at android.webkit.AccessibilityInjector.shouldInjectJavaScript(AccessibilityInjector.java:323)
     * at android.webkit.AccessibilityInjector.onPageFinished(AccessibilityInjector.java:282)
     * at android.webkit.WebViewClassic.onPageFinished(WebViewClassic.java:4129)
     * at android.webkit.CallbackProxy.handleMessage(CallbackProxy.java:325)
     * at android.os.Handler.dispatchMessage(Handler.java:99)
     * at android.os.Looper.loop(Looper.java:137)
     * at android.app.ActivityThread.main(ActivityThread.java:4794)
     * at java.lang.reflect.Method.invokeNative(Native Method)
     * at java.lang.reflect.Method.invoke(Method.java:511)
     * at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:789)
     * at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:556)
     * at dalvik.system.NativeStart.main(Native Method)
     *
     * 需要在{@link WebViewClient#onPageFinished(WebView, String)}之前的{@link WebViewClient#onPageStarted(WebView, String, Bitmap)}中检测并设置；
     */
    protected void fixedAccessibilityInjectorExceptionForOnPageFinished(String url) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN
                && getSettings().getJavaScriptEnabled()
                && mIsAccessibilityEnabledOriginal == null
                && isAccessibilityEnabled()) {
            try {
                try {
                    URLEncodedUtils.parse(new URI(url), null); // AccessibilityInjector.getAxsUrlParameterValue
                } catch (IllegalArgumentException e) {
                    if ("bad parameter".equals(e.getMessage())) {
                        mIsAccessibilityEnabledOriginal = true;
                        setAccessibilityEnabled(false);
                        LogUtils.safeCheckCrash(TAG, "fixedAccessibilityInjectorExceptionForOnPageFinished.url = " + url, e);
                    }
                }
            } catch (Throwable e) {
                if (LogUtils.isDebug()) {
                    LogUtils.e(TAG, "fixedAccessibilityInjectorExceptionForOnPageFinished", e);
                }
            }
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am.isEnabled();
    }

    private void setAccessibilityEnabled(boolean enabled) {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        try {
            Method setAccessibilityState = am.getClass().getDeclaredMethod("setAccessibilityState", boolean.class);
            setAccessibilityState.setAccessible(true);
            setAccessibilityState.invoke(am, enabled);
            setAccessibilityState.setAccessible(false);
        } catch (Throwable e) {
            if (LogUtils.isDebug()) {
                LogUtils.e(TAG, "setAccessibilityEnabled", e);
            }
        }
    }

    private void resetAccessibilityEnabled() {
        if (mIsAccessibilityEnabledOriginal != null) {
            setAccessibilityEnabled(mIsAccessibilityEnabledOriginal);
        }
    }

    /**
     * 向网页设置Cookie，设置Cookie后不需要页面刷新即可生效；
     *
     * 1、用一级域名设置Cookie：cookieManager.setCookie("360.cn", "key=value;path=/;domain=360.cn")（android所有版本都支持这种格式）;
     *    http://www.360doc.com/content/14/0903/22/9200790_406874810.shtml
     * 2、为何不能是“.360.cn”，在android2.3及以下版本，setCookie方法中URL参数必须是地址，如“360.cn”，而不能是“.360.cn”，否则存入webview.db-cookies表中的domain字段会为空导致无法在网页中生效;
     *    http://zlping.iteye.com/blog/1633213
     */
    public static void updateCookies(Context context, UpdateCookies updateCookies) {
        // 1、2.3及以下需要调用CookieSyncManager.createInstance；
        // 2、Samsung GTI9300 Android4.3，在调用cookieManager.setAcceptCookie之前不调用CookieSyncManager.createInstance会发生native崩溃：java.lang.UnsatisfiedLinkError: Native method not found: android.webkit.CookieManagerClassic.nativeSetAcceptCookie:(Z)V at android.webkit.CookieManagerClassic.nativeSetAcceptCookie(Native Method)
        CookieSyncManager.createInstance(context);
        CookieManager cookieManager = null;
        try {
            cookieManager = CookieManager.getInstance();
        } catch (AndroidRuntimeException e) { // 当webview内核apk正在升级时会发生崩溃（Meizu	m2 note Android5.1）
//            android.util.AndroidRuntimeException: android.content.pm.PackageManager$NameNotFoundException: com.android.webview
//            at android.webkit.WebViewFactory.getFactoryClass(WebViewFactory.java:174)
//            at android.webkit.WebViewFactory.getProvider(WebViewFactory.java:109)
//            at android.webkit.CookieManager.getInstance(CookieManager.java:42)
//            at android.webkit.safe.SafeWebView.updateCookies(XXX:479)
//            at com.xxx.accounts.manager.UserLoginManager.updateCookies(XXX:669)
//            at com.xxx.accounts.manager.UserLoginManager.onLoginSucceed(XXX:604)
//            at com.xxx.accounts.manager.UserLoginManager$6.onLoaded(XXX:567)
//            at com.xxx.accounts.manager.UserLoginManager$6.onRpcSuccess(XXX:550)
//            at com.xxx.accounts.api.auth.QucRpc$LocalHandler.handleMessage(XXX:62)
//            at android.os.Handler.dispatchMessage(Handler.java:111)
//            at android.os.Looper.loop(Looper.java:194)
//            at android.app.ActivityThread.main(ActivityThread.java:5637)
//            at java.lang.reflect.Method.invoke(Native Method)
//            at java.lang.reflect.Method.invoke(Method.java:372)
//            at com.android.internal.os.ZygoteInit$MethodAndArgsCaller.run(ZygoteInit.java:959)
//            at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:754)
//            Caused by: android.content.pm.PackageManager$NameNotFoundException: com.android.webview
//            at android.app.ApplicationPackageManager.getPackageInfo(ApplicationPackageManager.java:119)
//            at android.webkit.WebViewFactory.getFactoryClass(WebViewFactory.java:146)
//            ... 15 more
            if (LogUtils.isDebug()) {
                e.printStackTrace();
            }
        }
        if (cookieManager != null) {
            cookieManager.setAcceptCookie(true);
            if (updateCookies != null) {
                updateCookies.update(cookieManager);
            }
            CookieSyncManager.getInstance().sync();
        }
    }

    public interface UpdateCookies {
        void update(CookieManager cookieManager);
    }
}