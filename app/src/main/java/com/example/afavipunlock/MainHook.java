package com.example.afavipunlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 阿法算牌 VIP 永久解锁 - LSPosed / Xposed 模块
 *
 * 目标包名：com.example.af_flutter_ceshi
 * 策略：在app启动后，立即覆写SharedPreferences文件，写入所有VIP标志。
 *      之后app从SharedPreferences读到的就是已开通VIP状态。
 *
 * 工作原理：
 *   1. 等Flutter引擎初始化完（MainActivity.onCreate之后），
 *      在目标app进程里直接写SharedPreferences文件。
 *   2. 复盖所有可能的VIP key: is_vip, vip, member_type, is_paid 等。
 *   3. 写完不再Hook SharedPreferences方法（避免影响系统服务，触发ANR）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AFA_VIP_UNLOCK";
    private static final String TARGET_PACKAGE = "com.example.af_flutter_ceshi";

    // VIP写入目标
    private static final String[] PREF_FILES = {
            "FlutterSharedPreferences",  // Flutter官方默认SharedPreferences名
            "flutter_preferences",      // 备用
            "af_preferences",           // 项目自定义
            "afa_preferences",          // 项目自定义
            "user_preferences",         // 通用
    };

    // 写入的VIP字段
    private static final Map<String, Object> VIP_VALUES = new HashMap<>();
    static {
        // Boolean类
        VIP_VALUES.put("is_vip", true);
        VIP_VALUES.put("isVip", true);
        VIP_VALUES.put("vip", true);
        VIP_VALUES.put("vip_status", true);
        VIP_VALUES.put("vipStatus", true);
        VIP_VALUES.put("is_member", true);
        VIP_VALUES.put("isMember", true);
        VIP_VALUES.put("is_premium", true);
        VIP_VALUES.put("isPremium", true);
        VIP_VALUES.put("premium", true);
        VIP_VALUES.put("has_subscription", true);
        VIP_VALUES.put("hasSubscription", true);
        VIP_VALUES.put("paid", true);
        VIP_VALUES.put("is_paid", true);
        VIP_VALUES.put("isLogin", true);
        VIP_VALUES.put("is_login", true);
        VIP_VALUES.put("login_status", true);
        VIP_VALUES.put("loginStatus", true);

        // String类
        VIP_VALUES.put("vip_type", "permanent");
        VIP_VALUES.put("vipType", "permanent");
        VIP_VALUES.put("vip_level", "ultimate");
        VIP_VALUES.put("vipLevel", "ultimate");
        VIP_VALUES.put("member_type", "vip");
        VIP_VALUES.put("memberType", "vip");
        VIP_VALUES.put("user_level", "vip");
        VIP_VALUES.put("userLevel", "vip");
        VIP_VALUES.put("subscription_type", "permanent");
        VIP_VALUES.put("subscriptionType", "permanent");

        // Int类
        VIP_VALUES.put("vip_expire", Integer.MAX_VALUE);
        VIP_VALUES.put("vipExpire", Integer.MAX_VALUE);
        VIP_VALUES.put("expire_time", Integer.MAX_VALUE);
        VIP_VALUES.put("expireTime", Integer.MAX_VALUE);
        VIP_VALUES.put("login_type", 1);
        VIP_VALUES.put("loginType", 1);
    }

    private static Context appContext = null;
    private static boolean hasInjected = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        Log.i(TAG, "========================================");
        Log.i(TAG, "阿法算牌VIP解锁模块已加载");
        Log.i(TAG, "目标包名: " + lpparam.packageName);
        Log.i(TAG, "========================================");

        // Hook Application.attachBaseContext - app最早期的生命周期，能拿到app的dataDir
        XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attachBaseContext",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            appContext = (Context) param.args[0];
                            Log.i(TAG, "已捕获Context: " + appContext.getPackageName());
                            injectVip();
                        } catch (Throwable t) {
                            Log.e(TAG, "attachBaseContext 注入失败: " + t.getMessage(), t);
                        }
                    }
                }
        );

        // Hook MainActivity.onCreate - 避免Flutter界面显示时还没注入
        // 通过反射查找所有的 Activity onCreate
        // 因为包名是 com.example.af_flutter_ceshi，MainActivity 一般是 MainActivity
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Activity",
                    lpparam.classLoader,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 首次进入任意Activity时注入
                                if (!hasInjected && appContext != null) {
                                    injectVip();
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "onCreate 注入失败: " + t.getMessage());
                            }
                        }
                    }
            );
            Log.i(TAG, "Activity.onCreate Hook 加载完成");
        } catch (Throwable t) {
            Log.w(TAG, "Activity.onCreate Hook 加载失败: " + t.getMessage());
        }
    }

    /**
     * 注入VIP状态
     * 直接写SharedPreferences文件，避开方法Hook可能引起的ANR
     */
    private static void injectVip() {
        if (appContext == null) {
            Log.e(TAG, "appContext为空，无法注入");
            return;
        }
        if (hasInjected) {
            return;
        }
        hasInjected = true;

        try {
            // 关键：使用MODE_PRIVATE重新打开每个已知的SharedPreferences文件
            // 这会强制加载（如果存在）并创建（如果不存在）
            for (String prefFileName : PREF_FILES) {
                try {
                    SharedPreferences sp = appContext.getSharedPreferences(
                            prefFileName, Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = sp.edit();

                    // 先读取一次（触发实际加载）
                    Map<String, ?> existing = sp.getAll();
                    Log.i(TAG, "[" + prefFileName + "] 现有key数量: " + existing.size());
                    if (existing.size() > 0) {
                        Log.i(TAG, "[" + prefFileName + "] 现有keys: " + existing.keySet());
                    }

                    // 写入所有VIP字段
                    for (Map.Entry<String, Object> entry : VIP_VALUES.entrySet()) {
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        if (value instanceof Boolean) {
                            editor.putBoolean(key, (Boolean) value);
                        } else if (value instanceof Integer) {
                            editor.putInt(key, (Integer) value);
                        } else if (value instanceof Long) {
                            editor.putLong(key, (Long) value);
                        } else if (value instanceof Float) {
                            editor.putFloat(key, (Float) value);
                        } else if (value instanceof String) {
                            editor.putString(key, (String) value);
                        }
                    }

                    // 提交
                    boolean ok = editor.commit();
                    Log.i(TAG, "[" + prefFileName + "] VIP写入: " + (ok ? "成功 ✓" : "失败 ✗"));

                } catch (Throwable t) {
                    Log.w(TAG, "[" + prefFileName + "] 注入出错: " + t.getMessage());
                }
            }

            Log.i(TAG, "========================================");
            Log.i(TAG, "VIP状态注入完成！请打开APP测试");
            Log.i(TAG, "========================================");

        } catch (Throwable t) {
            Log.e(TAG, "注入过程异常: " + t.getMessage(), t);
        }
    }
}
