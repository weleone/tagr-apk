# Tagr release 混淆规则: 保留 MainActivity 与 JS 桥接方法, 其余交给 R8 压缩
-keep public class com.weleone.tagr.MainActivity { *; }
-keepclassmembers class com.weleone.tagr.MainActivity$ThemeBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
