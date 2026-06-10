# FeatBit Client-Side SDK for Kotlin (Android)

## Introduction

This is a Kotlin/Android client-side SDK for the 100% open-source feature flags management
platform [FeatBit](https://github.com/featbit/featbit), built because FeatBit does not
currently ship a native Kotlin SDK. Its architecture and API are based on the official
[.NET Client-Side SDK](https://github.com/featbit/featbit-dotnet-client-sdk); real-time
streaming sync is modeled on FeatBit's JS/React-Native SDK and `/streaming` wire protocol.

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

The SDK supports two modes:

- **Polling** (default) — fetches flags over HTTP at an interval (ported from the .NET client SDK):
  ```kotlin
  FBOptions.Builder(secret).polling("https://app-eval.featbit.co", interval = 5.minutes)
  ```
- **Streaming** — keeps a WebSocket open and receives flag changes in real time (modeled on the
  JS/RN SDK and FeatBit's `/streaming` protocol):
  ```kotlin
  FBOptions.Builder(secret).streaming("wss://app-eval.featbit.co")
  ```

Both feed the same in-memory store, so evaluation, `FlagTracker`, and `flagChanges` behave
identically regardless of mode. The synchronizer retries with exponential backoff and an
application-level heartbeat.

### Lifecycle-aware streaming (Android)

WebSockets don't survive backgrounding/doze, and reconnecting blindly wastes battery. The SDK
exposes neutral hooks so a streaming connection is dropped when the app backgrounds or goes
offline and re-established (with an immediate resync) on foreground / reconnect:

```kotlin
client.setForeground(true /* or false */)
client.setNetworkAvailable(true /* or false */)
```

Transitions to inactive are debounced by `FBOptions.Builder.backgroundGracePeriod(...)`
(default 20s) so brief app-switches/blips don't churn. Defaults are foreground + online, so a
client whose hooks are never called streams as before.

For Android, the optional **`featbit-client-android`** artifact wires these hooks to
`ProcessLifecycleOwner` + `ConnectivityManager` automatically — the core gains no extra
dependency:

```kotlin
// e.g. in Application.onCreate(), on the main thread
FBLifecycleConnector(this, fbClient).start()
```

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

## End-to-end tests

A full-stack E2E (`featbit-client/src/test/kotlin/co/featbit/client/e2e/`) drives a real
`FBClient` against a **real FeatBit stack** (Postgres + api-server + evaluation-server) started
with Testcontainers. It seeds a flag via FeatBit's management API, then asserts the SDK
evaluates it, observes a server-side toggle via polling, and re-identifies — over real HTTP.

It is **gated by `FEATBIT_E2E=1`** so the default unit-test run never requires Docker:

```bash
FEATBIT_E2E=1 ./gradlew :featbit-client:testDebugUnitTest --tests "co.featbit.client.e2e.*"
```

Requirements: a reachable Docker daemon. FeatBit's Postgres schema is vendored under
`src/test/resources/e2e/initdb/` (Apache-2.0, from `featbit/featbit`). CI runs this as a
separate job on every PR.

## Supported versions

- Android `minSdk 21`+, `compileSdk 34`.
- Kotlin 1.9+, JVM target 11.

## Backstage

This repository is [Backstage](https://backstage.io)-compatible: `catalog-info.yaml` registers
it as a Component, and this documentation is published via **TechDocs** (`mkdocs.yml` + `docs/`).

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](./CONTRIBUTING.md). To report a security
issue, see [SECURITY.md](./SECURITY.md).

## License

Apache-2.0 — see [LICENSE](./LICENSE) and [NOTICE](./NOTICE). FeatBit and the FeatBit SDKs are
works of the FeatBit project; this is an independent derivative port of their .NET client SDK
and is not affiliated with or endorsed by FeatBit.

Maintained by [Fluent Health](https://github.com/Fluent-Health).
