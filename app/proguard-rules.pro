# Zen Launcher uses no reflection or serialization libraries; the default
# optimize rules are sufficient. Strip verbose logging in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
