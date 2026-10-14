# App-specific R8 rules on top of proguard-android-optimize.txt and the libraries' consumer rules.
# The app uses no reflection, and kotlinx.serialization's generated serializers are kept by the rules the
# library ships, so nothing needs to be kept explicitly.

# Crash stack traces stay readable with the mapping file that CI uploads next to the APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
