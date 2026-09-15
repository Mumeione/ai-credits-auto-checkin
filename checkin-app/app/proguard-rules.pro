# =====================================================================
# R8 保留规则
# 本工程 release 开启了 isMinifyEnabled / isShrinkResources，
# 下列规则用于保住「靠反射加载、或靠类名字符串匹配」的入口，
# 删掉任何一条都可能导致运行时崩溃。
# =====================================================================

# ---------- WorkManager：Worker 由类名反射实例化 ----------
-keep class com.example.checkin.worker.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------- DataStore：凭据存储，涉及序列化与 Flow ----------
-keep class androidx.datastore.** { *; }
-keep class com.example.checkin.data.** { *; }

# ---------- 网络层 ----------
-keep class com.example.checkin.network.** { *; }

# ---------- Kotlin 元数据（去掉会在部分反射路径上报错） ----------
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ---------- OkHttp / Okio 在 Android 上的可选依赖，缺失属正常 ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- 其他常见噪音 ----------
-dontwarn javax.annotation.**
-dontwarn kotlin.**
