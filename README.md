# Sequenzy for Android

The official Android SDK for [Sequenzy](https://sequenzy.com), the email
marketing platform for SaaS. Identify contacts, track events, receive push notifications and show in-app
messages and content cards from an Android app. Events from your app start the same
sequences as your emails, and push and in-app messages are steps in them.
Full guide: [Mobile SDKs](https://docs.sequenzy.com/concepts/mobile-sdks).

## Install

```kotlin
dependencies {
    implementation("com.sequenzy:sequenzy-android:0.1.3")
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
Sequenzy.identifyUser(user.id)                  // or by your user ID, for users without an email
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
only at suspension points. Ask for notification permission on Android 13+ with
`Sequenzy.requestPushPermission()` (it prompts, or opens the notification
settings when they were turned off); push primers, in-app messages with an
"Asks for push" button, ask through the same flow, and registrations carry the
permission. With a `singleTop` or
`singleTask` launcher activity, call `Sequenzy.handleIntent(this, intent)` from
`onNewIntent`. Set
`Sequenzy.inAppListener` to veto messages or open links yourself, and
`Sequenzy.notificationIcon` for the small icon.

## Live Updates

Your backend starts, updates and ends Android Live Updates with the same
Sequenzy calls as iOS Live Activities. On Android 16 with promotion (API
36.1) they are promoted: top of the shade and lock screen, plus a status bar
chip. Earlier versions show an ongoing notification that updates in place (a
plain progress bar, the chip text as subtext). Observe each type in `Application.onCreate`, with the name
of your iOS attributes type:

```kotlin
SequenzyLiveUpdates.observe("DeliveryAttributes") { _, activity ->
    LiveUpdateContent(
        title = "Order ${activity.attributes["orderNumber"]}",
        text = activity.contentState["status"] as? String,
        shortCriticalText = "5 min",            // status bar chip, Android 16
        progress = (activity.contentState["progress"] as? Number)?.toInt(),
    )
}

// Optional: start, update and end from the app
SequenzyLiveUpdates.start("DeliveryAttributes", "order-1234", mapOf("orderNumber" to "1234"), mapOf("status" to "preparing"))
SequenzyLiveUpdates.end("order-1234", mapOf("status" to "delivered"))
```

The SDK receives the FCM data messages in `SequenzyMessagingService` (apps
with their own service call `SequenzyMessagingService.handleMessage`), applies
only pushes newer than the last one, reports the person swiping one away,
and removes running Live Updates on `reset()`. It needs notification
permission and declares `POST_PROMOTED_NOTIFICATIONS`.
`SequenzyLiveUpdates.canPostPromotedNotifications(context)` says whether
Android 16 promotes them for this app.

## Placements

Show in-app content inline, in a slot of your own layout, for a
[placement](https://docs.sequenzy.com/concepts/in-app-placements) defined in Sequenzy. The view is `GONE` while
the contact has nothing to show.

```kotlin
container.addView(SequenzyPlacementView(context, "home_hero"))
// XML: <com.sequenzy.android.SequenzyPlacementView android:tag="home_hero" ... />
// Compose: AndroidView(factory = { SequenzyPlacementView(it, "home_hero") })
```

To draw it yourself, use `SequenzyPlacements.observe("home_hero") { item -> }`,
`logDisplayed(item)` and `dismiss(item)`.

## Geofencing

`com.sequenzy:sequenzy-android-geofences` reports when the signed-in contact
enters or leaves your [geofences](https://docs.sequenzy.com/concepts/geofences),
with Google Play services location. It is a separate artifact so apps without
geofencing never pull in location code.

```kotlin
// Application.onCreate, after Sequenzy.configure(...)
SequenzyGeofences.start(this)
// once the user agreed and granted fine and background location
SequenzyGeofences.enable(context)
SequenzyGeofences.disable(context)   // consent withdrawn
```

The app declares and requests `ACCESS_FINE_LOCATION` and, on Android 10+,
`ACCESS_BACKGROUND_LOCATION` (Google Play asks apps that use it for a
declaration). Only identified contacts are tracked. The SDK registers the 49
nearest geofences plus one region around the device (lower `maxRegions` if the
app registers its own), fetches again when the device leaves it, and
re-registers after a reboot or app update.

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
| `sequenzy-android-geofences` | Geofence monitoring with Google Play services location |
| `example`          | One-screen app                                               |

## Develop

```bash
./gradlew :sequenzy-core:test                 # no Android SDK needed
./gradlew :sequenzy-android:assembleRelease :example:assembleDebug
```

The Android modules are only included when `ANDROID_HOME` or `local.properties`
points at an Android SDK. For real pushes and Live Updates in the example, put
your Firebase project's `google-services.json` (package `com.sequenzy.example`)
in `example/`; without it the example builds without Firebase. Tests run against `../contract/fixtures`. This repository is updated from Sequenzy's SDK
sources with every release.

## Links

- [Sequenzy](https://sequenzy.com): email marketing, push, SMS and in-app
  messages for SaaS, with automated sequences and AI-written emails.
- [Mobile SDK guide](https://docs.sequenzy.com/concepts/mobile-sdks)
- [Push notifications](https://docs.sequenzy.com/concepts/push-notifications)
  and [in-app messages](https://docs.sequenzy.com/concepts/in-app-messages)
- [Pricing](https://sequenzy.com/pricing)
- Other Sequenzy SDKs: [iOS](https://github.com/Sequenzy/sequenzy-swift), [React Native](https://github.com/Sequenzy/sequenzy-react-native), [Flutter](https://github.com/Sequenzy/sequenzy-flutter)

## Users without an email

For users who sign in without an email (a hidden Apple address, phone login),
mint the identity token for your user ID as `externalId` and call
`identifyUser` with the same ID (and the email, when you minted with it). Set
`userIdentityTokenProvider` to renew these tokens. See
[Users without an email](https://docs.sequenzy.com/concepts/mobile-sdks#users-without-an-email).
