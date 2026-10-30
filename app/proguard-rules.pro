# Framework DSP classes — audiofx effects are referenced from the DSP chain
-keep class android.media.audiofx.** { *; }

# JNI entry points (TFLite / LiteRT-LM native bindings)
-keepclasseswithmembernames class * { native <methods>; }

# Silence benign notes from bundled AI libs (their own consumer rules do the keeping)
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.**
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.apps.common.**
