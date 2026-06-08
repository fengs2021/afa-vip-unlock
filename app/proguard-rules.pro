# LSPosed模块 - ProGuard规则
# Xposed API 是 compileOnly 依赖，需要 keep
-keep class de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }

# 模块入口类
-keep class com.example.afavipunlock.MainHook { *; }

# 保留所有资源
-keep class com.example.afavipunlock.R { *; }
