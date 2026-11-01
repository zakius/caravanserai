# The release distribution (createReleaseDistributable): shrinking and renaming, as finimal's desktop build does, into
# one package with short names; no optimizing (ProGuard 7.8 and 7.10 fail on Kotlin 2.4's code). No line numbers or
# generic signatures, which nothing reads (1.2 MB): stack traces name methods, read through build/proguard-mapping.txt
# (roma ytm's: proguard-youtube.pro; retrace; each release attaches it). Exception names stay: the UI shows some.
-dontoptimize
-keepattributes *Annotation*, InnerClasses, Exceptions, EnclosingMethod
-repackageclasses ''
-allowaccessmodification
-keepnames class * extends java.lang.Throwable
-adaptresourcefilenames META-INF/services/*
-adaptresourcefilecontents META-INF/services/*

# Enums looked up by name (Sudachi's settings), which R8 keeps on its own and ProGuard doesn't.
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# Skia's native code (not Compose's rules, which keep all of Skia's Java side, 0.8 MB): its native methods keep their
# names, and the classes it reaches by name (their names in libskiko and the Windows DLL) stay whole. It calls back
# through Function0.
-keepclasseswithmembernames,includedescriptorclasses class org.jetbrains.sk** { native <methods>; }
-keep class
    org.jetbrains.skia.AnimationFrameInfo,
    org.jetbrains.skia.Color4f,
    org.jetbrains.skia.Drawable,
    org.jetbrains.skia.FontFamilyName,
    org.jetbrains.skia.FontFeature,
    org.jetbrains.skia.FontMetrics,
    org.jetbrains.skia.FontMgr,
    org.jetbrains.skia.FontVariation,
    org.jetbrains.skia.FontVariationAxis,
    org.jetbrains.skia.ImageInfo,
    org.jetbrains.skia.impl.Native,
    org.jetbrains.skia.IPoint,
    org.jetbrains.skia.IRect,
    org.jetbrains.skia.PaintFilterCanvas,
    org.jetbrains.skia.paragraph.DecorationStyle,
    org.jetbrains.skia.paragraph.LineMetrics,
    org.jetbrains.skia.paragraph.Shadow,
    org.jetbrains.skia.paragraph.TextBox,
    org.jetbrains.skia.Path,
    org.jetbrains.skia.PathSegment,
    org.jetbrains.skia.Point,
    org.jetbrains.skia.Rect,
    org.jetbrains.skia.RRect,
    org.jetbrains.skia.RSXform,
    org.jetbrains.skia.shaper.**,
    org.jetbrains.skia.skottie.Logger,
    org.jetbrains.skia.skottie.LogLevel,
    org.jetbrains.skia.svg.SVGLength,
    org.jetbrains.skia.svg.SVGPreserveAspectRatio,
    org.jetbrains.skiko.graphicapi.InternalDirectXApi,
    org.jetbrains.skiko.redrawer.Direct3DRedrawer,
    org.jetbrains.skiko.RenderExceptionsHandler,
    org.jetbrains.skiko.windows.JumpListBuilder$JumpListInteropItem,
    org.jetbrains.skiko.windows.JumpListException { *; }
-keep interface kotlin.jvm.functions.Function0 { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# kotlinx.serialization: its own rules (META-INF/proguard in its jar, which ProGuard doesn't read).
-keepclassmembers @kotlinx.serialization.Serializable class ** { static ** Companion; }
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembers class * { static <1> *; }
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclassmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class ** { public static ** INSTANCE; }
-keepclassmembers class <1> { public static <1> INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers public class **$$serializer { private ** descriptor; }

# Sudachi creates its plugins by class name and reads much of itself by reflection (RomaLattice too): kept whole, it
# is small. javax.json finds its provider by ServiceLoader.
-keep class com.worksap.nlp.sudachi.** { *; }
-keep class org.glassfish.json.JsonProviderImpl { <init>(); }

# ICU's transliteration reaches its classes without reflection, so ProGuard shrinks it as roma's code. Its entry
# points keep their names for :desktop:icuCheck, which runs the released jar.
-keep class com.ibm.icu.text.Transliterator { public static ** getInstance(java.lang.String); public java.lang.String transliterate(java.lang.String); }

# Optional parts of the libraries that roma doesn't use.
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.ibm.icu.**
-dontwarn javax.annotation.**
-dontnote **
# Sudachi's MethodHandle calls (signature-polymorphic, which ProGuard can't resolve).
-dontwarn com.worksap.nlp.sudachi.MMap
-dontwarn com.worksap.nlp.sudachi.SudachiCommandLine
