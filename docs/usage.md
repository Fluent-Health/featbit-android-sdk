# Usage

## Installation

```kotlin
dependencies {
    implementation("co.featbit:featbit-client:<version>")
    // optional: Android lifecycle/network auto-wiring
    implementation("co.featbit:featbit-client-android:<version>")
}
```

Requires `minSdk 21` and the `INTERNET` permission (declared by the library manifest).

## Quick start

```kotlin
import co.featbit.client.FBClientImpl
import co.featbit.client.model.FBUser
import co.featbit.client.options.FBOptions
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val options = FBOptions.Builder("<env-secret>")
    .polling("https://app-eval.featbit.co", interval = 5.minutes)
    .event("https://app-eval.featbit.co")
    .build()

val user = FBUser.builder("anonymous").name("anonymous").custom("role", "visitor").build()
val client = FBClientImpl(options, user)

val ready = client.start(timeout = 3.seconds) // suspend

// switch users after login
client.identify(FBUser.builder("bob-key").name("bob").custom("country", "FR").build())

// evaluate
val enabled = client.boolVariation("game-runner", default = false)
val detail = client.boolVariationDetail("game-runner", default = false) // .value, .reason

client.close()
```

## Evaluating flags

For each type there is a `*Variation` (value) and a `*VariationDetail` (`value` + `reason`):
`bool`, `string`, `int`, `float`, `double`. For JSON flags, use `stringVariation` to obtain the
JSON string.

## Tracking flag changes

```kotlin
// listener API
client.flagTracker.subscribe("game-runner") { e -> log(e.key, e.oldValue, e.newValue) }
// or idiomatic Kotlin
client.flagTracker.flagChanges.collect { e -> /* ... */ }
```

## Identifying users

The client always has a single current user. Set it at construction and change it with
`identify(...)` (e.g. on login). All evaluations refer to the current user.

## Offline mode & bootstrapping

```kotlin
val options = FBOptions.Builder()
    .offline(true)
    .bootstrap(savedFlags) // List<FeatureFlag>
    .build()
```

Use `client.allFlags()` to snapshot flags for later bootstrap.

## Logging

Provide an `FBLogger` via `FBOptions.Builder.logger(...)`; the default writes to Logcat
(falling back to stdout/stderr off-device).
