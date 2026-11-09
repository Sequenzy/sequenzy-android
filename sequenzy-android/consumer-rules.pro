-keep class com.sequenzy.android.SequenzyMessagingService { *; }
-keep class com.sequenzy.android.SequenzyNotificationOpenActivity { *; }
# Firebase Messaging is compileOnly: apps without it must still shrink.
-dontwarn com.google.firebase.**
# The custom HTML in-app bridge is called from JavaScript by name.
-keepclassmembers class com.sequenzy.android.InAppHtmlDialog$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
# Android 16 Live Updates use Notification.ProgressStyle behind an API 36
# check; apps that compile against an older SDK must still shrink.
-dontwarn android.app.Notification$ProgressStyle
-dontwarn android.app.Notification$ProgressStyle$**
