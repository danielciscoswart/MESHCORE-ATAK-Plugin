# ATAK release builds are obfuscated with a published mapping. A plugin must be
# rewritten with that same mapping or its references to ATAK classes (for
# example gov.tak.api.plugin.IServiceController) name classes that do not exist
# in the installed ATAK. ATAK then cannot find the plugin constructor and the
# load fails before any plugin code runs. atak-gradle-takdev sets the system
# property atak.proguard.mapping to the mapping it copies for the target ATAK
# version, in the pipeline and in local SDK mode. Every SDK sample carries this
# exact line. Do not remove it.
-applymapping <atak.proguard.mapping>

-dontskipnonpubliclibraryclasses
-dontshrink
-dontoptimize

# Stack traces in ATAK logs need line numbers.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions

# The 5.6 build page says a repackageclasses line, when present, must name the
# plugin. plugin.xml names the entry class below as a string, so it keeps its
# original name and members.
-repackageclasses atakplugin.MeshCoreAtak

-keep class * implements gov.tak.api.plugin.IPlugin {
}

-keep class dev.meshcore.atak.host.MeshCoreAtakPlugin {
    public <init>(...);
}

# ExperimentalGeoChatWatch implements ATAK's CommsLogger. No keep rule for its
# methods: with -applymapping R8 gives an overriding method the mapped name of the
# ATAK interface method, and an explicit keep would pin the unmapped name.

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class **.R$* {
    public static <fields>;
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-keep,allowobfuscation @interface gov.tak.api.annotation.DontObfuscate
-keep @gov.tak.api.annotation.DontObfuscate class * {*;}
-keepclassmembers class * {
    @gov.tak.api.annotation.DontObfuscate *;
}
