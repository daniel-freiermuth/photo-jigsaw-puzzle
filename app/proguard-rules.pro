# App-specific R8 rules on top of proguard-android-optimize.txt and the libraries' consumer rules.
# The app uses no reflection or serialisation by name, so nothing needs to be kept explicitly.

# Kotlin inserts these checks at Java-interop boundaries (parameters, platform-type values). Dropping them
# saves code size and a call per check. `!!` (Intrinsics.checkNotNull) is deliberately kept: code may rely
# on it throwing.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}

# Crash stack traces stay readable with the mapping file that CI uploads next to the APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
