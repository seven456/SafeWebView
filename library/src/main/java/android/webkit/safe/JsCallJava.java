package android.webkit.safe;

import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.HashMap;

public class JsCallJava {
    private final static String TAG = "JsCallJava";
    private final static String RETURN_RESULT_FORMAT = "{\"code\": %d, \"result\": %s}";
    private static final String MSG_PROMPT_HEADER = "SafeWebView:";
    private static final String KEY_METHOD = "method";
    private static final String KEY_TYPES = "types";
    private static final String KEY_ARGS = "args";
    private HashMap<String, Method> mMethodsMap;
    private String mInterfacedName;
    private String mPreloadInterfaceJS;
    private Object mInterfaceObj;

    public JsCallJava (Object interfaceObj, String interfaceName) {
        try {
            if (TextUtils.isEmpty(interfaceName)) {
                throw new Exception("injected name can not be null");
            }
            mInterfaceObj = interfaceObj;
            mInterfacedName = interfaceName;
            mMethodsMap = new HashMap<String, Method>();
            //获取自身声明的所有方法（包括public private protected）， getMethods会获得所有继承与非继承的方法
            Method[] methods = mInterfaceObj.getClass().getMethods();
            // js脚本备份：./library/doc/injected.js
            StringBuilder sb = new StringBuilder("javascript:(function(b){console.log(\"");
            sb.append(mInterfacedName);
            sb.append(" initialization begin\");var a={queue:[],callback:function(){var d=Array.prototype.slice.call(arguments,0);var c=d.shift();var e=d.shift();this.queue[c].apply(this,d);if(!e){delete this.queue[c]}}};");
            for (Method method : methods) {
                String sign;
                if ((sign = genJavaMethodSign(method)) == null) {
                    continue;
                }
                mMethodsMap.put(sign, method);
                sb.append(String.format("a.%s=", method.getName()));
            }

            sb.append("function(){var f=Array.prototype.slice.call(arguments,0);if(f.length<1){throw\"");
            sb.append(mInterfacedName);
            sb.append(" call error, message:miss method name\"}var e=[];for(var h=1;h<f.length;h++){var c=f[h];var j=typeof c;e[e.length]=j;if(j==\"function\"){var d=a.queue.length;a.queue[d]=c;f[h]=d}}var g=JSON.parse(prompt('");
            sb.append(MSG_PROMPT_HEADER);
            sb.append("'+JSON.stringify({");
            sb.append(KEY_METHOD);
            sb.append(":f.shift(),");
            sb.append(KEY_TYPES);
            sb.append(":e,");
            sb.append(KEY_ARGS);
            sb.append(":f})));if(g.code!=200){throw\"");
            sb.append(mInterfacedName);
            sb.append(" call error, code:\"+g.code+\", message:\"+g.result}return g.result};Object.getOwnPropertyNames(a).forEach(function(d){var c=a[d];if(typeof c===\"function\"&&d!==\"callback\"){a[d]=function(){return c.apply(a,[d].concat(Array.prototype.slice.call(arguments,0)))}}});b.");
            sb.append(mInterfacedName);
            sb.append("=a;console.log(\"");
            sb.append(mInterfacedName);
            sb.append(" initialization end\")})(window);");
            mPreloadInterfaceJS = sb.toString();
        } catch(Exception e){
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "init js error:" + e.getMessage());
            }
        }
    }

    private String genJavaMethodSign (Method method) {
        String sign = method.getName();
        Class[] argsTypes = method.getParameterTypes();
        int len = argsTypes.length;
        for (int k = 0; k < len; k++) {
            Class cls = argsTypes[k];
            if (cls == String.class) {
                sign += "_S";
            } else if (cls == int.class ||
                cls == long.class ||
                cls == float.class ||
                cls == double.class) {
                sign += "_N";
            } else if (cls == boolean.class) {
                sign += "_B";
            } else if (cls == JSONObject.class) {
                sign += "_O";
            } else if (cls == JsCallback.class) {
                sign += "_F";
            } else {
                sign += "_P";
            }
        }
        return sign;
    }

    public String getPreloadInterfaceJS () {
        return mPreloadInterfaceJS;
    }

    /**
     * 是否是特殊类型的内部消息；
     * @param message
     * @return
     */
    public boolean isSafeWebViewMsg(String message) {
        return message.startsWith(MSG_PROMPT_HEADER);
    }

    public String call (WebView webView, String jsonStr) {
        if (!TextUtils.isEmpty(jsonStr)) {
            try {
                jsonStr = jsonStr.substring(MSG_PROMPT_HEADER.length());
                JSONObject callJson = new JSONObject(jsonStr);
                String methodName = callJson.getString(KEY_METHOD);
                JSONArray argsTypes = callJson.getJSONArray(KEY_TYPES);
                JSONArray argsVals = callJson.getJSONArray(KEY_ARGS);
                String sign = methodName;
                int len = argsTypes.length();
                Object[] values = new Object[len];
                int numIndex = 0;
                String currType;

                for (int k = 0; k < len; k++) {
                    currType = argsTypes.optString(k);
                    if ("string".equals(currType)) {
                        sign += "_S";
                        values[k] = argsVals.isNull(k) ? null : argsVals.getString(k);
                    } else if ("number".equals(currType)) {
                        sign += "_N";
                        numIndex = numIndex * 10 + k + 1;
                    } else if ("boolean".equals(currType)) {
                        sign += "_B";
                        values[k] = argsVals.getBoolean(k);
                    } else if ("object".equals(currType)) {
                        sign += "_O";
                        values[k] = argsVals.isNull(k) ? null : argsVals.getJSONObject(k);
                    } else if ("function".equals(currType)) {
                        sign += "_F";
                        values[k] = new JsCallback(webView, mInterfacedName, argsVals.getInt(k));
                    } else {
                        sign += "_P";
                    }
                }

                Method currMethod = mMethodsMap.get(sign);

                // 方法匹配失败
                if (currMethod == null) {
                    return getReturn(jsonStr, 500, "not found method(" + sign + ") with valid parameters");
                }
                // 数字类型细分匹配
                if (numIndex > 0) {
                    Class[] methodTypes = currMethod.getParameterTypes();
                    int currIndex;
                    Class currCls;
                    while (numIndex > 0) {
                        currIndex = numIndex - numIndex / 10 * 10 - 1;
                        currCls = methodTypes[currIndex];
                        if (currCls == int.class) {
                            values[currIndex] = argsVals.getInt(currIndex);
                        } else if (currCls == long.class) {
                            //WARN: argsJson.getLong(k + defValue) will return a bigger incorrect number
                            values[currIndex] = Long.parseLong(argsVals.getString(currIndex));
                        } else {
                            values[currIndex] = argsVals.getDouble(currIndex);
                        }
                        numIndex /= 10;
                    }
                }

                return getReturn(jsonStr, 200, currMethod.invoke(mInterfaceObj, values));
            } catch (Exception e) {
                //优先返回详细的错误信息
                if (e.getCause() != null) {
                    return getReturn(jsonStr, 500, "method execute error:" + e.getCause().getMessage());
                }
                return getReturn(jsonStr, 500, "method execute error:" + e.getMessage());
            }
        } else {
            return getReturn(jsonStr, 500, "call data empty");
        }
    }

    private String getReturn (String reqJson, int stateCode, Object result) {
        String insertRes;
        if (result == null) {
            insertRes = "null";
        } else if (result instanceof String) {
            result = ((String) result).replace("\"", "\\\"");
            insertRes = "\"" + result + "\"";
        }/* else if (!(result instanceof Integer)
                && !(result instanceof Long)
                && !(result instanceof Boolean)
                && !(result instanceof Float)
                && !(result instanceof Double)
                && !(result instanceof JSONObject)) {    // 非数字或者非字符串的构造对象类型都要序列化后再拼接
            if (mGson == null) {
                mGson = new Gson();
            }
            insertRes = mGson.toJson(result);
        }*/ else {  //数字直接转化
            insertRes = String.valueOf(result);
        }
        String resStr = String.format(RETURN_RESULT_FORMAT, stateCode, insertRes);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, mInterfacedName + " call json: " + reqJson + " result:" + resStr);
        }
        return resStr;
    }
}