# Android entry points and Compose/OkHttp rules are supplied by AGP and the libraries.
# Models use explicit JSONObject fields; there is no reflective serialization.
-keepattributes Signature,InnerClasses,EnclosingMethod

# JLaTeXMath resolves predefined formulas/font helpers by name from bundled XML.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }

# Remote Markdown images are never loaded: the Markwon image module is not a dependency,
# Markdown images render as links that open only on the user's tap.
