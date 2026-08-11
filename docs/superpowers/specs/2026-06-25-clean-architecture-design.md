# Clean-Architecture Refactor — Design

**Date:** 2026-06-25
**Branch:** `refactor/clean-architecture`
**Status:** Design — awaiting approval before implementation

## Goal

Reorganise the FeatBit Android SDK source layout into a layered architecture (domain / application / data / wire) while preserving every public API contract and every test. The user-visible result is identical; the internal layout makes responsibilities explicit and reduces coupling.

## Constraints (non-negotiable)

1. **Public API is byte-compatible.** `FBClient`, `FBClientImpl`, `FBOptions`, `FBUser`, `FlagTracker`, `EvalDetail`, `FBLogger`, `FeatureFlag`, `DataSyncMode` — same FQNs, same signatures. Consumers' `import co.featbit.client.FBClient` (and similar) continue to compile.
2. **All 98 unit tests pass.** Both E2E tests pass against the real FeatBit stack.
3. **No behaviour change.** Pure file moves + import updates. No logic rewritten.
4. **No new Gradle modules.** Module split (Option C in brainstorm) is deferred — it would change Maven coordinates, which is external-consumer-visible.
5. **Files stay in their current sizes.** Splitting `StreamingDataSynchronizer` (266 LoC) is a separate refactor; out of scope.

## Current layout (problems)

```
co.featbit.client/
├── (root)            FBClient, FBClientImpl, FBLogger, LifecycleController
├── changetracker/    FlagTracker, FlagTrackerImpl
├── datasynchronizer/ DataSynchronizer + Null/Polling/Streaming
├── evaluation/       EvalDetail, EvalResult, Evaluator, ValueConverters
├── internal/         HTTP, endpoints, token, insight pipeline (junk drawer)
├── model/            FBUser, FeatureFlag, EndUser, Insight (mix of public + wire DTOs)
├── options/          DataSyncMode, FBOptions
└── store/            MemoryStore, DefaultMemoryStore, FlagValueChangedEvent
```

**Specific smells:**
- `internal/` mixes HTTP plumbing, batch dispatcher, token encoder, two endpoint clients.
- `model/` mixes public domain types (`FBUser`, `FeatureFlag`) with wire-format DTOs (`EndUser`, `Insight`).
- `LifecycleController` sits at the root next to public types but is internal.
- No clear dependency rule — `FBClientImpl` (orchestration) imports from 23 distinct internal packages.

## Target layout

```
co.featbit.client.*           (PUBLIC API — unchanged FQNs)
   FBClient, FBClientImpl, FBOptions, FBUser, FlagTracker, EvalDetail,
   FBLogger, FeatureFlag, DataSyncMode

co.featbit.client.domain/     (pure Kotlin, no Android / OkHttp / kotlinx-serialization)
   Evaluator, EvalResult, ValueConverters

co.featbit.client.app/        (application services / orchestration helpers)
   LifecycleController, FlagTrackerImpl,
   MemoryStore, DefaultMemoryStore, FlagValueChangedEvent

co.featbit.client.data.sync/  (synchronizer adapters)
   DataSynchronizer, NullDataSynchronizer, PollingDataSynchronizer, StreamingDataSynchronizer

co.featbit.client.data.http/  (HTTP plumbing — OkHttp-bound)
   FbApiClient, FBEndpoints, HttpConstants, GetUserFlags, ConnectionToken

co.featbit.client.data.insights/  (analytics pipeline)
   TrackInsight, NoopTrackInsight, HttpTrackInsight, InsightDispatcher

co.featbit.client.wire/       (kotlinx-serialization DTOs only)
   EndUser, Insight, VariationInsight, VariationData
```

### Why these names

- **`domain`**: pure types and the `MemoryStore` port (interface). Stays free of Android / OkHttp / app / data / wire deps. **Known carve-out:** `domain.EvalResult.Found` wraps `model.FeatureFlag`, which is `@Serializable` for wire-compat. We accept the transitive `kotlinx-serialization` reference here because moving `FeatureFlag` would break the public `FBClient.allFlags()` return type. A future refactor could split `FeatureFlag` into a pure-domain core + a wire DTO — out of scope here.
- **`app`**: holds application services + the `MemoryStore` adapter. `DefaultMemoryStore` lives here (concrete impl of the domain port). `LifecycleController` + `FlagTrackerImpl` are app services. `FlagValueChangedEvent` + `FlagChangeListener` stay in `store/` because they're part of the public `FlagTracker` API surface.
- **`data.sync`**, **`data.http`**, **`data.insights`**: each is one concrete adapter family. Replaces the `internal/` junk drawer.
- **`wire`**: kotlinx-serialization DTOs. They are NOT domain models — they are the on-wire format. Separating them prevents future refactors from accidentally letting `@Serializable` annotations creep into domain types.

### Dependency rule

```
data.* → app → domain
data.* → wire
app → domain (interface + types)
PUBLIC (root) → app, domain, data.*  (orchestration glue)
PUBLIC store/ → (nothing in this refactor — pure data types)
```

No `domain` → `app` / `data.*` / `wire`. No `app` → `data.*`. No `wire` → `domain`. Enforced by code review (no Gradle-level boundary yet).

### `MemoryStore` as a port (ports/adapters)

Issue surfaced by Task 1 audit: `Evaluator` (now in `domain/`) imports `MemoryStore`. If `MemoryStore` moves to `app/`, the dependency arrow becomes `domain → app` — a rule violation.

Resolution: **`MemoryStore` (interface) lives in `domain/` as a port. `DefaultMemoryStore` (impl) lives in `app/` as the adapter.** This is hexagonal architecture as the textbook intends. `Evaluator` depends on the domain port; the implementation is injected.

Single-file pair split:
- `domain/MemoryStore.kt` — interface (was `store/MemoryStore.kt`).
- `app/DefaultMemoryStore.kt` — impl (was `store/DefaultMemoryStore.kt`).
- `store/FlagValueChangedEvent.kt` — STAYS (public API; referenced by `FlagTracker.subscribe(FlagChangeListener)`). Contains both `FlagValueChangedEvent` data class and `FlagChangeListener` fun interface.

## File-by-file move plan

Public files stay (no move):
- `FBClient.kt`, `FBClientImpl.kt`, `FBLogger.kt`, `FBOptions.kt`, `FBUser.kt`, `FeatureFlag.kt`, `FlagTracker.kt`, `EvalDetail.kt`, `DataSyncMode.kt`

Internal moves:
| Current location | New location |
|---|---|
| `evaluation/Evaluator.kt` | `domain/Evaluator.kt` |
| `evaluation/EvalResult.kt` | `domain/EvalResult.kt` |
| `evaluation/ValueConverters.kt` | `domain/ValueConverters.kt` |
| `LifecycleController.kt` | `app/LifecycleController.kt` |
| `changetracker/FlagTrackerImpl.kt` | `app/FlagTrackerImpl.kt` |
| `store/MemoryStore.kt` | `domain/MemoryStore.kt` *(port — used by `Evaluator`)* |
| `store/DefaultMemoryStore.kt` | `app/DefaultMemoryStore.kt` *(adapter)* |
| `store/FlagValueChangedEvent.kt` | **STAYS** at `store/FlagValueChangedEvent.kt` *(public API via `FlagTracker`)* |
| `datasynchronizer/*` | `data/sync/*` |
| `internal/FbApiClient.kt` | `data/http/FbApiClient.kt` |
| `internal/FBEndpoints.kt` | `data/http/FBEndpoints.kt` |
| `internal/HttpConstants.kt` | `data/http/HttpConstants.kt` |
| `internal/GetUserFlags.kt` | `data/http/GetUserFlags.kt` |
| `internal/ConnectionToken.kt` | `data/http/ConnectionToken.kt` |
| `internal/TrackInsight.kt` | `data/insights/TrackInsight.kt` |
| `internal/InsightDispatcher.kt` | `data/insights/InsightDispatcher.kt` |
| `model/EndUser.kt` | `wire/EndUser.kt` |
| `model/Insight.kt` | `wire/Insight.kt` |

`FBOptions.kt` stays in `options/` to preserve its existing FQN `co.featbit.client.options.FBOptions`. *(Existing import; consumers depend on it.)*

`FlagTracker.kt` (public interface) stays in `changetracker/` for the same reason.

## Tests

Test packages mirror source packages. Each test file moves to match the new location of the class under test. Test logic does not change. Imports update mechanically.

The `78 unit tests + 2 e2e + ... = 98 total` must all stay green at every step.

## Verification gates (at each commit)

1. `./gradlew :featbit-client:compileDebugKotlin` — green
2. `./gradlew :featbit-client:testDebugUnitTest` — 98 pass / 0 fail / 2 e2e-skipped
3. `./gradlew :featbit-client:assembleDebug :featbit-client-android:assembleDebug :example-app:assembleDebug` — green
4. Adversarial audit on the diff — zero blocking findings
5. (Once, before push) E2E with `FEATBIT_E2E=1` against Colima FeatBit — both tests pass

## Risk + mitigation

| Risk | Mitigation |
|---|---|
| Test imports break | Each commit moves one logical group + updates all callers in same commit |
| Public API accidentally moves | Pre-commit grep: `git diff --name-only HEAD~1 -- '*FBClient*' '*FBOptions*' '*FBUser*' '*EvalDetail*' '*FBLogger*' '*FeatureFlag*' '*FlagTracker.kt'` must show no `R` (rename) entries for these files |
| E2E regresses | Run after every 2-3 logical commits, not just at the end |
| Cyclic dependencies introduced | After every commit, grep for `domain/*.kt` importing `data.*` or `wire.*` — must be empty |

## Out of scope

- Module splits (Option C): Maven artifact compatibility concern; defer.
- Splitting `StreamingDataSynchronizer` (266 LoC): separate refactor.
- Behaviour changes (e.g. the cancellation-propagation follow-up flagged in `TrackInsightTest`): separate commits.
- Removing the `internal/` package entirely (one or two files may stay if a clean home doesn't exist).

## Implementation strategy (one commit per layer)

1. **Commit 1:** Move `domain/` (Evaluator, EvalResult, ValueConverters). Update imports in callers + tests. Verify gates.
2. **Commit 2:** Move `app/` group (LifecycleController, FlagTrackerImpl, store/*). Update imports + tests.
3. **Commit 3:** Move `data/sync/` (datasynchronizer/*). Update imports + tests.
4. **Commit 4:** Move `data/http/` (FbApiClient, FBEndpoints, ConnectionToken, GetUserFlags, HttpConstants). Update imports + tests.
5. **Commit 5:** Move `data/insights/` (TrackInsight, InsightDispatcher). Update imports + tests.
6. **Commit 6:** Move `wire/` (EndUser, Insight). Update imports + tests.
7. **Commit 7:** Final audit + E2E re-run + delete the now-empty `evaluation/`, `changetracker/`, `store/`, `datasynchronizer/`, `internal/`, `model/` directories.

Each commit independently green. If any commit fails, roll back just that commit and reconsider — every earlier commit stands on its own.
