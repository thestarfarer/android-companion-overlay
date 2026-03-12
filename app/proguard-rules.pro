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

# MCP models
-keep class com.starfarer.companionoverlay.mcp.** { *; }

# ═══════════════════════════════════════════════════════════════════════════
# Architecture Components
# ═══════════════════════════════════════════════════════════════════════════

# Event classes (sealed class hierarchy — keep hierarchy, not all members)
-keep class com.starfarer.companionoverlay.event.** { <init>(...); }

# Repository layer (Koin-constructed singletons)
-keep class com.starfarer.companionoverlay.repository.** { <init>(...); }

# UI helpers (Fragment-based, need constructors for recreation)
-keep class com.starfarer.companionoverlay.ui.** { <init>(...); }

# ViewModel classes
-keep class com.starfarer.companionoverlay.viewmodel.** { <init>(...); }

# Koin modules (need to be accessible for DI wiring)
-keep class com.starfarer.companionoverlay.di.** { <init>(...); }

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
-keep class com.starfarer.companionoverlay.CompanionCarAppService { <init>(...); }
-keep class com.starfarer.companionoverlay.CompanionCarSession { <init>(...); }
-keep class com.starfarer.companionoverlay.CompanionMainScreen { <init>(...); }
-keep class com.starfarer.companionoverlay.CompanionModelScreen { <init>(...); }
-keep class com.starfarer.companionoverlay.CompanionResponseScreen { <init>(...); }

# ═══════════════════════════════════════════════════════════════════════════
# Services (must be kept for manifest references)
# ═══════════════════════════════════════════════════════════════════════════

-keep class com.starfarer.companionoverlay.CompanionOverlayService { <init>(...); }
-keep class com.starfarer.companionoverlay.CompanionAccessibilityService { <init>(...); }

# ═══════════════════════════════════════════════════════════════════════════
# Activities & Fragments
# ═══════════════════════════════════════════════════════════════════════════

-keep class com.starfarer.companionoverlay.MainActivity { <init>(...); }
-keep class com.starfarer.companionoverlay.SettingsActivity { <init>(...); }
-keep class com.starfarer.companionoverlay.SettingsFragment { <init>(...); }

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

# ═══════════════════════════════════════════════════════════════════════════
# Strip debug/verbose logging in release builds
# ═══════════════════════════════════════════════════════════════════════════

-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
