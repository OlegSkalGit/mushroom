# ProGuard / R8 optimization rules for Mushroom Offline Navigator
-keepclasseswithmembers class * extends android.database.sqlite.SQLiteOpenHelper {
    public <init>(...);
}
