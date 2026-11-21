# Keep kotlinx.serialization generated serializers, @Serializable models, and domain enums
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.momentledger.app.** { *; }
-keep,includedescriptorclasses class com.momentledger.app.**$$serializer { *; }
-keepclassmembers class com.momentledger.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.momentledger.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum com.momentledger.app.domain.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Room database, entities, DAOs, and TypeConverters
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class com.momentledger.app.data.db.** { *; }
-keep @androidx.room.Dao class com.momentledger.app.data.db.** { *; }
-keep class com.momentledger.app.data.db.LedgerConverters { *; }
-dontwarn androidx.room.paging.**

# Keep ML Kit bundled Chinese OCR JNI & component registrars
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Keep PDFBox-Android and BouncyCastle security handlers for encrypted bank PDF statements
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**

# Keep manifest-registered Application, Activity, Quick Settings Tiles, and Receivers
-keep public class com.momentledger.app.MomentLedgerApp {
    public <init>();
    public void onCreate();
}
-keep public class com.momentledger.app.MainActivity {
    public <init>();
    *;
}
-keep public class com.momentledger.app.quickentry.QuickEntryTileService {
    public <init>();
    public *;
    protected *;
}
-keep public class com.momentledger.app.quickentry.ScreenshotOcrTileService {
    public <init>();
    public *;
    protected *;
}
-keep public class com.momentledger.app.quickentry.QuickEntryBootReceiver {
    public <init>();
    public *;
}
-keep public class * extends android.service.quicksettings.TileService {
    public <init>();
    public *;
    protected *;
}
-keep public class * extends android.content.BroadcastReceiver {
    public <init>();
}
