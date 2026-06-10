# FeatBit Kotlin/Android SDK

A Kotlin/Android client-side SDK for the open-source feature-flag platform
[FeatBit](https://github.com/featbit/featbit), built because FeatBit ships no native Kotlin
SDK. Its architecture and API are based on FeatBit's official
[.NET client SDK](https://github.com/featbit/featbit-dotnet-client-sdk); real-time streaming
sync is modeled on FeatBit's JS/React-Native SDK and `/streaming` wire protocol.

This is a **client-side** SDK for single-user contexts (mobile, desktop, embedded) — not for
multi-user systems such as web servers.

## Features

- Typed flag evaluation (`bool`/`string`/`int`/`float`/`double`, plus `*Detail` with reasons)
- **Polling** and real-time **WebSocket streaming** synchronization
- Lifecycle-aware streaming on Android (pause on background/offline, resync on resume)
- Flag-change tracking via listeners and a Kotlin `Flow`
- Offline mode and bootstrap
- Built on coroutines, OkHttp, and kotlinx.serialization

## Modules

| Module | Purpose |
|---|---|
| `featbit-client` | The core SDK (Android library, AAR) |
| `featbit-client-android` | Optional glue: `FBLifecycleConnector` wiring lifecycle/network to the client |

## Next steps

- [Usage](usage.md) — install, initialize, evaluate, track changes
- [Data synchronization](data-sync.md) — polling vs. streaming and lifecycle behavior
