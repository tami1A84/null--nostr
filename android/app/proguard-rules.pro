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

# JNA (required: R8 must not rename peer field or Structure subclass fields/methods)
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.Callback { *; }
-dontwarn com.sun.jna.**

# UniFFI-generated bindings (nurunuru Rust engine)
-keep class uniffi.nurunuru.** { *; }

# rust-nostr SDK (UniFFI/JNA-based — must not be obfuscated)
-keep class rust.nostr.** { *; }
-dontwarn rust.nostr.**

# Bouncy Castle (ProofMode PGP signing)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Play Integrity API (ProofMode device attestation)
-keep class com.google.android.play.core.integrity.** { *; }
-dontwarn com.google.android.play.core.integrity.**
