package com.example.afavipunlock;

import android.content.SharedPreferences;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 阿法算牌 VIP 永久解锁 - LSPosed / Xposed 模块
 *
 * 目标包名：com.example.af_flutter_ceshi
 * 策略：Hook SharedPreferences 层，拦截所有VIP相关key的读取，
 *       强制返回已开通VIP状态。同时拦截写入操作，防止app将VIP状态覆盖为false。
 *
 * 工作原理：
 *   阿法算牌是Flutter应用，业务逻辑在Dart层（libapp.so）。
 *   VIP状态通过 Flutter shared_preferences 插件存储，
 *   底层使用 Android SharedPreferences Java API。
 *   我们在Java层Hook SharedPreferences，Dart层无需修改即可生效。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AFA_VIP_UNLOCK";
    private static final String TARGET_PACKAGE = "com.example.af_flutter_ceshi";

    // ============================================================
    // VIP关键key列表
    // 如果发现实际使用的key名不在列表中，添加到此处即可
    // ============================================================
    private static final String[] VIP_KEYS = {
            "is_vip",
            "isVip",
            "vip",
            "vip_status",
            "vipStatus",
            "member_type",
            "memberType",
            "isMember",
            "is_premium",
            "isPremium",
            "premium",
            "subscription_status",
            "subscriptionStatus",
            "has_subscription",
            "hasSubscription",
            "paid",
            "is_paid",
            "user_level",
            "userLevel",
            "login_status",
            "isLogin",
            "is_login",
            "flutter.is_vip",
            "flutter.vip",
            "huiyuan",
            "vip_type",
            "vipType",
    };

    // ============================================================
    // 检测是否是VIP相关key
    // ============================================================
    private static boolean isVipKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        for (String vipKey : VIP_KEYS) {
            if (lower.equals(vipKey.toLowerCase()) ||
                    lower.contains(vipKey.toLowerCase()) ||
                    vipKey.toLowerCase().contains(lower)) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // 根据value类型，返回"已开通VIP"对应的值
    // ============================================================
    private static Object getVipValue(Class<?> returnType, Object originalValue) {
        if (returnType == boolean.class || returnType == Boolean.class) {
            return true;
        } else if (returnType == int.class || returnType == Integer.class) {
            return 1;
        } else if (returnType == long.class || returnType == Long.class) {
            return 1L;
        } else if (returnType == float.class || returnType == Float.class) {
            return 1.0f;
        } else if (returnType == String.class) {
            return "vip";
        } else {
            return originalValue;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 只处理目标应用
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        Log.i(TAG, "========================================");
        Log.i(TAG, "阿法算牌VIP解锁模块已加载");
        Log.i(TAG, "目标包名: " + lpparam.packageName);
        Log.i(TAG, "========================================");

        try {
            // ============================================
            // Hook 1: SharedPreferencesImpl 所有读取方法
            // ============================================
            Class<?> spImplClass = XposedHelpers.findClass(
                    "android.app.SharedPreferencesImpl",
                    lpparam.classLoader
            );

            // --- getBoolean ---
            XposedHelpers.findAndHookMethod(spImplClass, "getBoolean",
                    String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];
                            if (isVipKey(key)) {
                                Log.i(TAG, ">>> getBoolean(\"" + key + "\") -> true (注入)");
                                param.setResult(true);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && isVipKey((String) param.args[0])) {
                                Log.i(TAG, ">>> getBoolean(\"" + param.args[0] + "\") = " + param.getResult());
                            }
                        }
                    }
            );

            // --- getInt ---
            XposedHelpers.findAndHookMethod(spImplClass, "getInt",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];
                            if (isVipKey(key)) {
                                Log.i(TAG, ">>> getInt(\"" + key + "\") -> 1 (注入)");
                                param.setResult(1);
                            }
                        }
                    }
            );

            // --- getString ---
            XposedHelpers.findAndHookMethod(spImplClass, "getString",
                    String.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];
                            if (isVipKey(key)) {
                                Log.i(TAG, ">>> getString(\"" + key + "\") -> \"vip\" (注入)");
                                param.setResult("vip");
                            }
                        }
                    }
            );

            // --- getLong ---
            XposedHelpers.findAndHookMethod(spImplClass, "getLong",
                    String.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];
                            if (isVipKey(key)) {
                                Log.i(TAG, ">>> getLong(\"" + key + "\") -> 1 (注入)");
                                param.setResult(1L);
                            }
                        }
                    }
            );

            Log.i(TAG, "SharedPreferences 读取Hook完成 ✓");

            // ============================================
            // Hook 2: SharedPreferences.Editor 写入操作
            // 防止app将VIP状态设为false
            // ============================================
            try {
                Class<?> editorImplClass = XposedHelpers.findClass(
                        "android.app.SharedPreferencesImpl$EditorImpl",
                        lpparam.classLoader
                );

                XposedHelpers.findAndHookMethod(editorImplClass, "putBoolean",
                        String.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String key = (String) param.args[0];
                                boolean value = (boolean) param.args[1];
                                if (isVipKey(key) && !value) {
                                    Log.i(TAG, ">>> Editor.putBoolean(\"" + key + "\", false) -> 拦截并改为true");
                                    param.args[1] = true;
                                }
                            }
                        }
                );

                XposedHelpers.findAndHookMethod(editorImplClass, "putString",
                        String.class, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String key = (String) param.args[0];
                                String value = (String) param.args[1];
                                if (isVipKey(key)) {
                                    Log.i(TAG, ">>> Editor.putString(\"" + key + "\", \"" + value + "\") -> 改为\"vip\"");
                                    param.args[1] = "vip";
                                }
                            }
                        }
                );

                // Editor.apply() 和 commit() 执行后再次强制写入VIP状态
                XposedHelpers.findAndHookMethod(editorImplClass, "apply",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // apply()后不需要额外操作，put阶段已拦截
                            }
                        }
                );

                XposedHelpers.findAndHookMethod(editorImplClass, "commit",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // 同上
                            }
                        }
                );

                Log.i(TAG, "SharedPreferences 写入拦截完成 ✓");
            } catch (XposedHelpers.ClassNotFoundError e) {
                Log.w(TAG, "EditorImpl hook失败 (非关键): " + e.getMessage());
            }

            // ============================================
            // Hook 3: (可选) Flutter MethodChannel
            // 如果SharedPreferences层Hook已经生效，此层作为兜底
            // ============================================
            try {
                Class<?> methodChannelClass = XposedHelpers.findClass(
                        "io.flutter.plugin.common.MethodChannel",
                        lpparam.classLoader
                );

                XposedHelpers.findAndHookMethod(methodChannelClass, "invokeMethod",
                        String.class, Object.class,
                        XposedHelpers.findClass("io.flutter.plugin.common.MethodChannel$Result", lpparam.classLoader),
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String method = (String) param.args[0];
                                Object args = param.args[1];

                                // 只处理shared_preferences相关的调用
                                if (method != null && method.startsWith("get")) {
                                    // 尝试从args中提取key
                                    if (args != null) {
                                        String argsStr = args.toString();
                                        for (String vipKey : VIP_KEYS) {
                                            if (argsStr.toLowerCase().contains(vipKey.toLowerCase())) {
                                                Log.i(TAG, ">>> MethodChannel." + method + " args包含VIP key: " + argsStr);
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                );

                Log.i(TAG, "Flutter MethodChannel Hook完成 ✓");
            } catch (XposedHelpers.ClassNotFoundError e) {
                Log.i(TAG, "MethodChannel未找到 (Flutter可能使用不同版本): " + e.getMessage());
            }

            Log.i(TAG, "========================================");
            Log.i(TAG, "全部Hook加载完成！VIP已永久解锁");
            Log.i(TAG, "========================================");

        } catch (Exception e) {
            Log.e(TAG, "Hook加载失败: " + e.getMessage(), e);
            XposedBridge.log(TAG + " Hook加载失败: " + e);
        }
    }
}
