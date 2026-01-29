# Add project specific ProGuard rules here.

# Keep Nostr model classes
-keep class com.example.nostr.domain.model.** { *; }
-keep class com.example.nostr.data.model.** { *; }
-keep class com.example.nostr.nostr.event.** { *; }

# Keep secp256k1
-keep class fr.acinq.secp256k1.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
