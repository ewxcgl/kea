# ---- KEA -----------------------------------------------------------------
# Keep line numbers for readable production stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room / Compose / OkHttp ship their own rules; these cover our own surface.

# Entry points instantiated by the framework.
-keep class app.kea.KeaApp { *; }
-keep class app.kea.MainActivity { *; }
-keep class app.kea.service.ShareService { *; }
-keep class app.kea.service.ShareService$Companion { *; }

# kotlinx.serialization generates a companion .serializer() per @Serializable
# class. R8 cannot see it is used, so keep the serializer surface.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.kea.**$$serializer { *; }
-keepclassmembers class app.kea.** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# BouncyCastle ships consumer rules; this covers the provider surface it looks
# up reflectively. Only Ed25519, ChaCha20Poly1305 and HKDF are used, so R8
# discards the rest of the 8 MB jar.
-keep class org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.modes.ChaCha20Poly1305 { *; }
-keep class org.bouncycastle.crypto.generators.HKDFBytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.HKDFParameters { *; }
-keep class org.bouncycastle.crypto.params.AEADParameters { *; }
-keep class org.bouncycastle.crypto.params.KeyParameter { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# MapLibre resolves renderers and styles reflectively.
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**
-dontwarn org.maplibre.geojson.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# LocationManager is used instead of Play Services, so strip the Play Services
# classes some transitive dependency still references.
-dontwarn com.google.android.gms.**

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Never let R8 inline or merge the crypto surface; keep it boring and reviewable.
-keep,allowobfuscation,allowshrinking class app.kea.crypto.** { *; }

# Compose already ships rules; silence the noisy-but-harmless ones.
-dontwarn androidx.compose.**