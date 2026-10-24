# Android entry points and Compose/OkHttp rules are supplied by AGP and the libraries.
# Models use explicit JSONObject fields; there is no reflective serialization.
-keepattributes Signature,InnerClasses,EnclosingMethod
# JNA maps these interfaces and Structure fields to native Vosk symbols.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
# JNA's desktop-only window helpers reference AWT; Android never calls them.
-dontwarn java.awt.**

# JLaTeXMath resolves predefined formulas/font helpers by name from bundled XML.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }

# Remote Markdown images are never loaded: the Markwon image module is not a dependency,
# Markdown images render as links that open only on the user's tap.

-keep class dev.luma.agent.core.LlamaNative { *; }
-keep class dev.luma.agent.core.NativeReply { *; }
-keep class dev.luma.agent.core.NativeProgress { *; }
