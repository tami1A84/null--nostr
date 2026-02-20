# NuruNuru Android ProGuard rules

# Keep Nostr data models
-keep class io.nurunuru.app.data.models.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.nurunuru.**$$serializer { *; }
-keepclassmembers class io.nurunuru.** {
    *** Companion;
}
-keepclasseswithmembers class io.nurunuru.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# secp256k1
-keep class fr.acinq.secp256k1.** { *; }

# Coil
-dontwarn coil.**
