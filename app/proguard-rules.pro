# 保持 MainActivity 不被混淆
-keep class com.teemo.launcher.MainActivity { *; }

# 保持所有内部类
-keep class com.teemo.launcher.MainActivity$* { *; }

# 保持 Parcelable 相关
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保持 Serializable
-keep class * implements java.io.Serializable { *; }
