# Keep rules for release builds (R8 已启用)

# ===== Gson 序列化模型：字段名必须保留，否则序列化后全是 null =====
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# 本项目的 RPC / 配置 / 桥接模型
-keep class com.mcp.server.server.RpcMessage { *; }
-keep class com.mcp.server.server.RpcMessage$** { *; }
-keep class com.mcp.server.server.Settings { *; }
-keep class com.mcp.server.server.Settings$** { *; }
-keep class com.mcp.server.bridge.** { *; }
-keep class com.mcp.server.tools.ToolDefinition { *; }
-keep class com.mcp.server.tools.ToolDefinition$** { *; }
-keepclassmembers class com.mcp.server.** {
    <fields>;
}

# ===== QuickJS：JNI native 方法与其回调入口不能被重命名 =====
-keep class com.quickjs.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===== Shizuku：Binder IPC 依赖完整类名与方法签名 =====
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
-keepclassmembers class * extends android.content.ContentProvider {
    public <init>();
}

# ===== Android 组件：Manifest 中声明的类不能被移走 =====
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application

# ===== 序列化 / 反射兜底 =====
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留行号便于排查线上崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
