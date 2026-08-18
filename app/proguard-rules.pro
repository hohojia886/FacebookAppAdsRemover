# [2026-08-17 00:48] Security Hardening: Keep rules for Xposed and DexKit

# Keep the Xposed module entry point
-keep class tn.loukious.facebookappadsremover.Module { *; }

# Keep LibXposed API classes
-keep class io.github.libxposed.api.** { *; }

# Keep DexKit classes and JNI
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** {
    native <methods>;
}

# Keep common reflection targets if necessary
-keepattributes Signature,EnclosingMethod,InnerClasses,AnnotationDefault,*Annotation*,Exceptions

# Keep everything in our package if we want to be safe with reflection
# -keep class tn.loukious.facebookappadsremover.** { *; }
