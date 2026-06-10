# Data synchronization

The SDK supports two modes; both feed the same in-memory store, so evaluation, `FlagTracker`,
and `flagChanges` behave identically regardless of mode.

## Polling (default)

Fetches flags over HTTP at an interval (ported from the .NET client SDK):

```kotlin
FBOptions.Builder(secret).polling("https://app-eval.featbit.co", interval = 5.minutes)
```

## Streaming

Keeps a WebSocket open and receives flag changes in real time (modeled on the JS/RN SDK and
FeatBit's `/streaming` protocol):

```kotlin
FBOptions.Builder(secret).streaming("wss://app-eval.featbit.co")
```

The synchronizer retries with exponential backoff and an application-level heartbeat.

## Lifecycle-aware streaming (Android)

WebSockets don't survive backgrounding/doze, and reconnecting blindly wastes battery. The SDK
exposes neutral hooks so a streaming connection is dropped when the app backgrounds or goes
offline and re-established (with an immediate resync) on foreground / reconnect:

```kotlin
client.setForeground(true /* or false */)
client.setNetworkAvailable(true /* or false */)
```

Transitions to inactive are debounced by `FBOptions.Builder.backgroundGracePeriod(...)`
(default 20s). Defaults are foreground + online, so a client whose hooks are never called
streams as before.

The optional `featbit-client-android` module wires these hooks to `ProcessLifecycleOwner` +
`ConnectivityManager` automatically:

```kotlin
// e.g. in Application.onCreate(), on the main thread
FBLifecycleConnector(this, fbClient).start()
```
