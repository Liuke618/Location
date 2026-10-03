# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 高德 SDK(3dmap/search/location)
-keep class com.amap.api.** {*;}
-keep class com.autonavi.** {*;}
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
# 高德定位可选 GNSS 组件未随包分发,R8 missing_rules.txt 生成规则(assembleRelease 实测)
-dontwarn com.amap.ams.gnss.GnssSoftLocator

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**