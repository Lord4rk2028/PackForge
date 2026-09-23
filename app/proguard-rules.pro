# ─── PackForge / R8 ───────────────────────────────────────────────────────
# Reglas de minificación/obfuscación para el build de release.

# Atributos necesarios para Gson (anotaciones y firmas genéricas)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses,EnclosingMethod
# Trazas con número de línea legibles en release (crash reports)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Gson (usa reflexión sobre los modelos) ──
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
# Modelos serializados con Gson por nombre de campo (Addon, etc.)
-keep class com.packforge.app.domain.model.** { *; }

# ── OkHttp / Okio: warnings de clases JDK/AOSP no usadas ──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── WebView: conservar métodos expuestos a JavaScript ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}