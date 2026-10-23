# kotlinx.serialization: mantém os serializers gerados das classes @Serializable
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.m3uplayer.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.m3uplayer.app.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Google Cast: a classe de opções é referenciada só pelo nome, no manifesto
-keep class com.m3uplayer.app.cast.CastOptionsProvider { *; }
