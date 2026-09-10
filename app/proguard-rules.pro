# Facebook App Ads Remover ProGuard / R8 Keep Rules

# Opt 4.2: Preserve essential Xposed entry points so Xposed framework can reflectively instantiate the module
-keep class tn.loukious.facebookappadsremover.Module {
    public *;
}
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public *;
}
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources {
    public *;
}

# Preserve DexKit required JNI classes and native method contracts
-keep class org.luckypray.dexkit.** {
    public protected *;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# Allow R8 access modification for maximum dead-code elimination and method inlining in release builds
-allowaccessmodification
