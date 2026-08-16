# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# LSPosed 透過 assets/xposed_init 的類別全名字串反射載入進入點，
# R8 看不到這個引用關係，必須明確 keep 住，否則 release 版會被改名/砍掉導致模組載入失敗
-keep class tn.loukious.facebookappadsremover.Module { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage

# 如果您的專案在 WebView 中使用 JavaScript 介面，請取消註解以下內容並指定類別路徑
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留行號資訊，便於排查崩潰日誌
-keepattributes SourceFile,LineNumberTable

# 隱藏原始原始檔名（通常配合 SourceFile 使用）
-renamesourcefileattribute SourceFile

# 防止 XposedBridge 相關類別被混淆（雖然通常是 compileOnly，但保險起見）
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# DexKit 混淆保護
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**
