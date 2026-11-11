# --- jaudiotagger (references java.awt / javax.imageio that do not exist on Android) ---
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn org.jaudiotagger.**
-keep class org.jaudiotagger.** { *; }

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.lyra.music.**$$serializer { *; }
-keepclassmembers class dev.lyra.music.** { *** Companion; }
-keepclasseswithmembers class dev.lyra.music.** { kotlinx.serialization.KSerializer serializer(...); }

# --- OkHttp / Retrofit ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# --- Media3 ---
-dontwarn androidx.media3.**
