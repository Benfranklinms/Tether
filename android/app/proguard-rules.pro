# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.benfranklin.tether.**$$serializer { *; }
-keepclassmembers class dev.benfranklin.tether.** { *** Companion; }
-keepclasseswithmembers class dev.benfranklin.tether.** { kotlinx.serialization.KSerializer serializer(...); }

# BouncyCastle lightweight API is used directly (no JCA provider lookup).
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.crypto.modes.ChaCha20Poly1305 { *; }
