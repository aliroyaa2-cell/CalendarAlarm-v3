# Keep source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room entities (just in case minify is enabled later)
-keep class com.alaimtiaz.calendaralarm.data.** { *; }
