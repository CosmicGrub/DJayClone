# DJayClone R8 rules.
# Media3 and AndroidX ship consumer rules; these cover what this app adds.

# The custom audio processors are looked up by the renderers factory by type, not
# by reflection, but keep their names readable in crash reports.
-keepnames class com.oblivion.djayclone.*AudioProcessor

# Strip debug/verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Keep line numbers so a stack trace in the crash log is still useful.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
