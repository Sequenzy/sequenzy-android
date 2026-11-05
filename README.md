# Sequenzy for Android

The official Android SDK for [Sequenzy](https://sequenzy.com), the email
marketing platform for SaaS. Identify contacts, track events, receive push notifications and show in-app
messages and content cards from an Android app. Events from your app start the same
sequences as your emails, and push and in-app messages are steps in them.
Full guide: [Mobile SDKs](https://docs.sequenzy.com/concepts/mobile-sdks).

## Install

```kotlin
dependencies {
    implementation("com.sequenzy:sequenzy-android:0.1.1")
    implementation("com.google.firebase:firebase-messaging:24.1.0") // for push
}
```

minSdk 21. The SDK compiles against Firebase Messaging but does not bring it in.

## Use

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Sequenzy.configure(
            this,
            publicKey = "seq_pk_...",       // key with your package name under Allowed app IDs
            companyId = "your-company-id",
            identityTokenProvider = { email -> myBackend.sequenzyToken(email) },
        )
    }
}

Sequenzy.identify(user.email)                   // after sign-in
Sequenzy.track("workout_completed", mapOf("minutes" to 30))
Sequenzy.reset()                                 // on sign-out
val cards = Sequenzy.fetchCards()                // suspend; inbox screen
```

`SequenzyMessagingService` (declared by the SDK's manifest) registers FCM
tokens and shows pushes that arrive in the foreground; taps on background
notifications are reported when they open your app. Apps with their own
`FirebaseMessagingService` call `SequenzyMessagingService.handleNewToken` and
`handleMessage` from it. The identity token provider should suspend (for example with a suspending
HTTP client) rather than block a thread: the SDK times it out after 20 seconds
only at suspension points. Ask for `POST_NOTIFICATIONS` on Android 13+. With a `singleTop` or
`singleTask` launcher activity, call `Sequenzy.handleIntent(this, intent)` from
`onNewIntent`. Set
`Sequenzy.inAppListener` to veto messages or open links yourself, and
`Sequenzy.notificationIcon` for the small icon.

## Custom HTML messages

[Custom HTML in-app messages](https://docs.sequenzy.com/concepts/in-app-html-messages) (surveys, Braze-style custom HTML) show
automatically in a sandboxed `WebView` (no file or content access, no mixed
content, no third-party cookies; each message clears its origin's storage
and cookies before its scripts run). Their JavaScript bridge closes the message,
opens links through `SequenzyInAppListener`, and logs events and attributes
for the identified contact. Set `Sequenzy.showsHtmlMessages = false` before
`configure` to leave them in the feed, or call
`fetchMessages(includeHtml = true)` to present them yourself. The SDK's
consumer ProGuard rules keep the bridge when your app is minified.

## Layout

| Module             | Contents                                                     |
| ------------------ | ------------------------------------------------------------ |
| `sequenzy-core`    | Protocol client, identity, event queue, payload parsing. Plain Kotlin/JVM. |
| `sequenzy-android` | Facade, SharedPreferences store, FCM service, notifications, in-app presenter |
| `example`          | One-screen app                                               |

## Develop

```bash
./gradlew :sequenzy-core:test                 # no Android SDK needed
./gradlew :sequenzy-android:assembleRelease :example:assembleDebug
```

The Android modules are only included when `ANDROID_HOME` or `local.properties`
points at an Android SDK. Tests run against `../contract/fixtures`. This repository is updated from Sequenzy's SDK
sources with every release.

## Links

- [Sequenzy](https://sequenzy.com): email marketing, push, SMS and in-app
  messages for SaaS, with automated sequences and AI-written emails.
- [Mobile SDK guide](https://docs.sequenzy.com/concepts/mobile-sdks)
- [Push notifications](https://docs.sequenzy.com/concepts/push-notifications)
  and [in-app messages](https://docs.sequenzy.com/concepts/in-app-messages)
- [Pricing](https://sequenzy.com/pricing)
- Other Sequenzy SDKs: [iOS](https://github.com/Sequenzy/sequenzy-swift), [React Native](https://github.com/Sequenzy/sequenzy-react-native), [Flutter](https://github.com/Sequenzy/sequenzy-flutter)
