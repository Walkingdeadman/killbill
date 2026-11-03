# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.killbill.** { *; }
-keepclassmembers class com.killbill.** {
    *** Companion;
}
-keepclasseswithmembers class com.killbill.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (security-crypto 内部) 引用的编译期注解，运行时不需要
-dontwarn com.google.errorprone.annotations.**

# Tink 大量反射，R8 full mode 下必须整包保留，否则 EncryptedSharedPreferences 启动即炸
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.android.gms.security.** { *; }
