# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class dev.shuchir.hcgateway.data.remote.** { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Gson
-keep class com.google.gson.** { *; }
-keepattributes EnclosingMethod

# Sentry
-keep class io.sentry.** { *; }
