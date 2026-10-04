# Mewsic ProGuard / R8 Rules for Maximum Performance & Minimal APK Size

# Optimization passes for low-end devices
-repackageclasses
-allowaccessmodification

# Keep data classes and serialization if needed
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep native methods if any
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep view constructors for XML inflation
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}
