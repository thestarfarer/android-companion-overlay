# CompanionOverlay ProGuard Rules

# ═══════════════════════════════════════════════════════════════════════════
# Kotlin Serialization
# ═══════════════════════════════════════════════════════════════════════════

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializable data classes
-keep,includedescriptorclasses class com.starfarer.companionoverlay.**$$serializer { *; }
-keepclassmembers class com.starfarer.companionoverlay.** {
    *** Companion;
}
-keepclasseswithmembers class com.starfarer.companionoverlay.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ═══════════════════════════════════════════════════════════════════════════
# Data Classes
# ═══════════════════════════════════════════════════════════════════════════

-keep class com.starfarer.companionoverlay.CharacterPreset { *; }
-keep class com.starfarer.companionoverlay.CharacterPreset$Companion { *; }

# API models
-keep class com.starfarer.companionoverlay.api.** { *; }

# ═══════════════════════════════════════════════════════════════════════════
# Architecture Components
# ═══════════════════════════════════════════════════════════════════════════

# Event classes (sealed class hierarchy)
-keep class com.starfarer.companionoverlay.event.** { *; }

# Repository layer
-keep class com.starfarer.companionoverlay.repository.** { *; }

# UI helpers
-keep class com.starfarer.companionoverlay.ui.** { *; }

# ViewModel classes
-keep class com.starfarer.companionoverlay.viewmodel.** { *; }

# Koin modules
-keep class com.starfarer.companionoverlay.di.** { *; }

# ═══════════════════════════════════════════════════════════════════════════
# Third-party Libraries
# ═══════════════════════════════════════════════════════════════════════════

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ONNX Runtime (Silero VAD)
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ═══════════════════════════════════════════════════════════════════════════
# Android Auto / Car App Library
# ═══════════════════════════════════════════════════════════════════════════

-keep class androidx.car.app.** { *; }
-keep class com.starfarer.companionoverlay.CompanionCarAppService { *; }
-keep class com.starfarer.companionoverlay.CompanionCarSession { *; }
-keep class com.starfarer.companionoverlay.CompanionMainScreen { *; }
-keep class com.starfarer.companionoverlay.CompanionModelScreen { *; }
-keep class com.starfarer.companionoverlay.CompanionResponseScreen { *; }

# ═══════════════════════════════════════════════════════════════════════════
# Services (must be kept for manifest references)
# ═══════════════════════════════════════════════════════════════════════════

-keep class com.starfarer.companionoverlay.CompanionOverlayService { *; }
-keep class com.starfarer.companionoverlay.CompanionAccessibilityService { *; }

# ═══════════════════════════════════════════════════════════════════════════
# Activities & Fragments
# ═══════════════════════════════════════════════════════════════════════════

-keep class com.starfarer.companionoverlay.MainActivity { *; }
-keep class com.starfarer.companionoverlay.SettingsActivity { *; }
-keep class com.starfarer.companionoverlay.AssistActivity { *; }
-keep class com.starfarer.companionoverlay.SettingsFragment { *; }

# ═══════════════════════════════════════════════════════════════════════════
# Interfaces (for Koin injection)
# ═══════════════════════════════════════════════════════════════════════════

-keep interface com.starfarer.companionoverlay.VoiceInputHost { *; }
-keep interface com.starfarer.companionoverlay.ConversationManager$Listener { *; }
-keep interface com.starfarer.companionoverlay.BubbleManager$Host { *; }

# ═══════════════════════════════════════════════════════════════════════════
# Enums
# ═══════════════════════════════════════════════════════════════════════════

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
