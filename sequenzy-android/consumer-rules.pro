-keep class com.sequenzy.android.SequenzyMessagingService { *; }
-keep class com.sequenzy.android.SequenzyNotificationOpenActivity { *; }
# Firebase Messaging is compileOnly: apps without it must still shrink.
-dontwarn com.google.firebase.**
