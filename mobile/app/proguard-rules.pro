# ── Retrofit ──────────────────────────────────────────────────────────────
# Retrofit reflects on generic parameters; InnerClasses is required to use
# Signature and EnclosingMethod is required to use InnerClasses.
-keepattributes Signature, InnerClasses, EnclosingMethod
# Retrofit reflects on method and parameter annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes *Annotation*

-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# R8 full mode (default on AGP 8+) creates Retrofit interfaces via a Proxy and,
# seeing no subtypes, would otherwise replace all return values with null.
# Explicitly keep the annotated interfaces.
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface * extends <1>

# R8 full mode strips generic signatures from types that are not kept. Retrofit
# reads the generic argument off Call/Response and — for suspend functions — off
# the Continuation the compiler injects. Without these the release build throws
# "java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType" on the
# first request and all sync fails. (Retrofit < 2.10 doesn't bundle these.)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── Gson ──────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Gson uses reflection over model fields; keep generic signatures of any
# TypeToken so List<Foo>/Map<..> deserialisation survives full-mode R8.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── API + payload models (reflected on by Gson) ─────────────────────────────
-keep class com.roamly.data.api.** { *; }

# ── OkHttp ──────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
