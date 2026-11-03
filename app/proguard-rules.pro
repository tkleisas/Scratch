# Keep JNI entry points.
-keepclasseswithmembernames class * {
    native <methods>;
}

# JNI names are resolved by their exact Java class and method names.
-keep class com.tkleisas.scratchdj.audio.DjEngine { *; }
