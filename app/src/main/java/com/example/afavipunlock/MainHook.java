package com.example.afavipunlock;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 阿法算牌 VIP 永久解锁 - LSPatch embedded 模块
 *
 * 目标包名：com.example.af_flutter_ceshi (Flutter + 梆梆加固)
 * 模式：LSPatch 嵌入式（无需 root，无 LSPosed 框架在 /proc/self/maps）
 *
 * 工作原理：
 *   1. 不 hook Application.attachBaseContext（梆梆壳拦截，全局 hook 会卡开屏）
 *   2. 只 hook com.example.af_flutter_ceshi.MainActivity.onCreate
 *      - LSPosed 引擎会在 MainActivity 类被 ClassLoader 加载时延迟 hook
 *      - 此时梆梆已经解密完真实 dex，MainActivity 是真实类
 *   3. 第一次 onCreate 回调时，写 DataStore preferences_pb + SharedPreferences XML
 *   4. 写完即返回，不阻塞 UI
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AFA_VIP";
    private static final String TARGET_PACKAGE = "com.example.af_flutter_ceshi";
    private static final String MAIN_ACTIVITY = "com.example.af_flutter_ceshi.MainActivity";

    private static volatile boolean hasInjected = false;

    // Flutter shared_preferences 在 Android 端实际写 androidx.datastore，
    // 文件路径是 filesDir/datastore/shared_preferences.preferences_pb
    // 同时为了兼容老式插件，我们也写 shared_prefs/FlutterSharedPreferences.xml

    // VIP key -> value
    private static final Map<String, Object> VIP_BOOL = new LinkedHashMap<>();
    private static final Map<String, String> VIP_STR = new LinkedHashMap<>();
    private static final Map<String, Integer> VIP_INT = new LinkedHashMap<>();
    static {
        VIP_BOOL.put("flutter.is_vip", true);
        VIP_BOOL.put("flutter.vip", true);
        VIP_BOOL.put("flutter.is_paid", true);
        VIP_BOOL.put("flutter.is_premium", true);
        VIP_BOOL.put("flutter.vip_status", true);
        VIP_STR.put("flutter.vip_type", "permanent");
        VIP_STR.put("flutter.user_level", "ultimate");
        VIP_STR.put("flutter.member_type", "vip");
        VIP_INT.put("flutter.expire", Integer.MAX_VALUE);
        VIP_INT.put("flutter.vip_expire", Integer.MAX_VALUE);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // Hook MainActivity.onCreate - 真实 MainActivity 类在梆梆解密后才会被加载
        // LSPosed 引擎会延迟到类出现时再 hook
        try {
            XposedHelpers.findAndHookMethod(
                    MAIN_ACTIVITY,
                    lpparam.classLoader,
                    "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (hasInjected) return;
                            try {
                                Object activity = param.thisObject;
                                if (activity == null) return;
                                Context ctx = ((android.app.Activity) activity).getApplicationContext();
                                if (ctx == null) return;
                                hasInjected = true;
                                injectAll(ctx);
                            } catch (Throwable t) {
                                // 不打印日志，避免 IO
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            // 类尚未加载，LSPosed 引擎会延迟尝试
        }
    }

    private static void injectAll(Context ctx) {
        try {
            writeDataStoreProto(ctx);
        } catch (Throwable ignored) {}
        try {
            writeSharedPreferencesXml(ctx);
        } catch (Throwable ignored) {}
    }

    /**
     * 写 androidx.datastore 的 protobuf 文件
     * 路径：filesDir/datastore/shared_preferences.preferences_pb
     */
    private static void writeDataStoreProto(Context ctx) throws Exception {
        File filesDir = ctx.getFilesDir();
        File dsDir = new File(filesDir, "datastore");
        // 不调 mkdirs，DataStore 自己会创建；但首次写入时还不存在会失败
        if (!dsDir.exists()) {
            // DataStore 会自己创建，但只在该文件被请求时；我们提前写不创建会导致失败
            // 让 Flutter 端第一次读时自动创建空 pb，之后我们再写
            // 这里不主动创建目录
        }
        File dsFile = new File(dsDir, "shared_preferences.preferences_pb");
        if (!dsFile.exists()) {
            // 第一次运行，等 Flutter 端自己创建
            return;
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, Object> e : VIP_BOOL.entrySet()) {
            writeMapEntry(out, e.getKey(), buildBoolValue((Boolean) e.getValue()));
        }
        for (Map.Entry<String, String> e : VIP_STR.entrySet()) {
            writeMapEntry(out, e.getKey(), buildStringValue(e.getValue()));
        }
        for (Map.Entry<String, Integer> e : VIP_INT.entrySet()) {
            writeMapEntry(out, e.getKey(), buildIntValue(e.getValue()));
        }

        FileOutputStream fos = new FileOutputStream(dsFile);
        try {
            fos.write(out.toByteArray());
        } finally {
            fos.close();
        }
    }

    /**
     * 写老式 SharedPreferences XML 兜底
     */
    private static void writeSharedPreferencesXml(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(
                "FlutterSharedPreferences", Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, Object> e : VIP_BOOL.entrySet()) {
            ed.putBoolean(e.getKey(), (Boolean) e.getValue());
        }
        for (Map.Entry<String, String> e : VIP_STR.entrySet()) {
            ed.putString(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Integer> e : VIP_INT.entrySet()) {
            ed.putInt(e.getKey(), e.getValue());
        }
        ed.apply();
    }

    // ===== protobuf 字节构造 =====
    private static byte[] buildBoolValue(boolean b) {
        return new byte[]{(byte) 0x08, b ? (byte) 0x01 : (byte) 0x00};
    }
    private static byte[] buildIntValue(int v) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x18); // Value.integer tag
        writeVarint(o, v);
        return o.toByteArray();
    }
    private static byte[] buildStringValue(String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x2A); // Value.string tag
        writeVarint(o, b.length);
        try { o.write(b); } catch (Exception ignored) {}
        return o.toByteArray();
    }
    private static void writeMapEntry(java.io.ByteArrayOutputStream out, String key, byte[] valueMsg) throws Exception {
        byte[] kb = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int entryLen = 2 + kb.length + 2 + valueMsg.length;
        out.write(0x0A); // map entry tag
        writeVarint(out, entryLen);
        out.write(0x0A); // key tag
        writeVarint(out, kb.length);
        out.write(kb);
        out.write(0x12); // value tag
        writeVarint(out, valueMsg.length);
        out.write(valueMsg);
    }
    private static void writeVarint(java.io.OutputStream out, int v) throws java.io.IOException {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
    }
}
