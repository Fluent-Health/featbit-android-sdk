# FeatBit Client-Side SDK for Kotlin (Android)

## Introduction

This is a Kotlin/Android client-side SDK for the 100% open-source feature flags management
platform [FeatBit](https://github.com/featbit/featbit). It is a port of the official
[.NET Client-Side SDK](https://github.com/featbit/featbit-dotnet-client-sdk), built because
FeatBit does not currently ship a native Kotlin SDK.

Be aware, this is a **client-side** SDK intended for use in a single-user context — mobile,
desktop, or embedded applications. It is **not** intended for multi-user systems such as web
servers. For server-side use, see FeatBit's server SDKs.

## Getting Started

### Installation

Add the dependency (coordinates depend on where you publish it):

```kotlin
dependencies {
    implementation("co.featbit:featbit-client:<version>")
}
```

The library requires `minSdk 21` and the `INTERNET` permission (declared by the library
manifest and merged automatically).

### Prerequisite

Before using the SDK, obtain your environment secret and SDK URLs:

- [How to get the environment secret](https://docs.featbit.co/sdk/faq#how-to-get-the-environment-secret)
- [How to get the SDK URLs](https://docs.featbit.co/sdk/faq#how-to-get-the-sdk-urls)

> The polling URL is the streaming URL with the protocol swapped:
> `ws(s)://<host>` → `http(s)://<host>`. Both resolve to the same evaluation server.

### Quick Start

```kotlin
import co.featbit.client.FBClientImpl
import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// setup SDK options
val options = FBOptions.Builder("<replace-with-your-env-secret>")
    .polling("https://app-eval.featbit.co", interval = 5.minutes)
    .event("https://app-eval.featbit.co")
    .build()

// use an anonymous user as the initial user
val anonymousUser = FBUser.builder("anonymous")
    .name("anonymous")
    .custom("role", "visitor")
    .build()

val client = FBClientImpl(options, anonymousUser)

// start and wait up to 3 seconds for the client to be ready (suspend function)
val success = client.start(timeout = 3.seconds)
if (!success) {
    println("FBClient failed to initialize. Variation calls will use fallback values.")
}

// after the user logs in, switch users and fetch their flags
val authenticatedUser = FBUser.builder("a-unique-key-of-bob")
    .name("bob")
    .custom("country", "FR")
    .build()
client.identify(authenticatedUser)

// evaluate a boolean flag
val flagKey = "game-runner"
val enabled = client.boolVariation(flagKey, default = false)

// evaluate with reason
val detail = client.boolVariationDetail(flagKey, default = false)
println("flag '$flagKey' = ${detail.value} (reason: ${detail.reason})")

// subscribe to flag changes (listener)
client.flagTracker.subscribe(flagKey) { e ->
    println("'${e.key}' changed from '${e.oldValue}' to '${e.newValue}'")
}

// ...or collect them idiomatically from a coroutine
// client.flagTracker.flagChanges.collect { e -> /* ... */ }

client.close()
```

A runnable Android example lives in [`example-app/`](./example-app).

## SDK

### Async model

This SDK aligns with the FeatBit JS/React-Native SDK model (a promise-like
`waitUntilReady()` plus an update event stream), expressed idiomatically in Kotlin:

- `start(...)` and `identify(...)` are **suspend** functions.
- Flag evaluations (`boolVariation`, etc.) are **synchronous** reads from the in-memory store.
- Flag changes are delivered via the `FlagTracker` listener API **and** a `flagChanges: Flow`.

### Data synchronization

The SDK currently synchronizes via **polling** (matching the .NET client SDK). Set the
polling interval via `FBOptions.Builder.polling(uri, interval)`. WebSocket **streaming**
sync (as used by the JS/RN SDK) is a planned future addition.

### FBClient

`FBClient` is the heart of the SDK. Create a **single instance** for the lifetime of your app
(register it as a singleton in your DI graph — Hilt/Koin/etc.).

### FBUser

`FBUser` describes the user that flags are evaluated for. The only mandatory attribute is
`key`. Add any number of custom string attributes with `custom(key, value)`:

```kotlin
val bob = FBUser.builder("a-unique-key-of-bob")
    .name("bob")
    .custom("age", "15")
    .custom("country", "FR")
    .build()
```

### Evaluating flags

For each type there is a `*Variation` method returning the value and a `*VariationDetail`
returning an `EvalDetail` (`value` + `reason`):

- `boolVariation` / `boolVariationDetail`
- `stringVariation` / `stringVariationDetail`
- `intVariation` / `intVariationDetail`
- `floatVariation` / `floatVariationDetail`
- `doubleVariation` / `doubleVariationDetail`

> For JSON flags, use `stringVariation` to obtain the JSON string (a typed `jsonVariation`
> is on the roadmap).

### Tracking flag changes

```kotlin
// all flags
client.flagTracker.subscribe { e -> /* ... */ }
// a specific flag
client.flagTracker.subscribe("game-runner") { e -> /* ... */ }
// unsubscribe with the same listener reference
```

### Offline mode & bootstrapping

```kotlin
val options = FBOptions.Builder()
    .offline(true)
    .bootstrap(savedFlags) // List<FeatureFlag>
    .build()
```

In offline mode no network calls are made; evaluations use bootstrap data (or fallback
values). Use `client.allFlags()` to snapshot the current flags for later bootstrap.

### Logging

Provide a `FBLogger` via `FBOptions.Builder.logger(...)` to route SDK logs into your app.
The default `DefaultLogger` writes to Logcat (falling back to stdout/stderr off-device).

## Building

This repo ships sources but not the Gradle wrapper JAR. Generate it once with a local Gradle
(8.5+), then build:

```bash
gradle wrapper --gradle-version 8.9
./gradlew :featbit-client:assembleRelease   # produce the AAR
./gradlew :featbit-client:test              # run unit tests
```

## Supported versions

- Android `minSdk 21`+, `compileSdk 34`.
- Kotlin 1.9+, JVM target 11.

## License

Apache-2.0 — see [LICENSE](./LICENSE). FeatBit and the FeatBit SDKs are trademarks/works of
the FeatBit project; this is an independent community port of their .NET client SDK.
