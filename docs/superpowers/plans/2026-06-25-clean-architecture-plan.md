# Clean-Architecture Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganise FeatBit SDK source files into a layered architecture (`domain/`, `app/`, `data/sync/`, `data/http/`, `data/insights/`, `wire/`) while keeping every public API FQN unchanged and every test passing.

**Architecture:** Layered. `domain` is pure Kotlin (no Android, OkHttp, or kotlinx-serialization). `app` holds application services. `data.*` packages each hold one concrete adapter family. `wire` holds kotlinx-serialization DTOs. Public types stay at their existing root packages so `import co.featbit.client.FBClient` and similar continue to compile for external consumers.

**Tech Stack:** Kotlin 1.9, Gradle (Android), OkHttp 4.12, kotlinx-serialization 1.6, JUnit 4, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-06-25-clean-architecture-design.md`

---

## File Structure (target)

```
featbit-client/src/main/kotlin/co/featbit/client/
├── FBClient.kt                       (public — unchanged)
├── FBClientImpl.kt                   (public — unchanged location; imports updated)
├── FBLogger.kt                       (public — unchanged)
├── FeatureFlag.kt                    (public — new location: was model/FeatureFlag.kt → root)
│   * NOTE: stays at co.featbit.client.model.FeatureFlag to preserve FQN — see Task 6 note.
│
├── changetracker/FlagTracker.kt      (public — unchanged)
│
├── options/
│   ├── DataSyncMode.kt               (public — unchanged)
│   └── FBOptions.kt                  (public — unchanged)
│
├── model/
│   ├── FBUser.kt                     (public — unchanged)
│   └── FeatureFlag.kt                (public — unchanged)
│
├── evaluation/
│   └── EvalDetail.kt                 (public — unchanged)
│
├── store/
│   └── FlagValueChangedEvent.kt      (public — STAYS; holds FlagValueChangedEvent + FlagChangeListener referenced by public FlagTracker API)
│
├── domain/                            ← NEW
│   ├── Evaluator.kt                  (was: evaluation/Evaluator.kt)
│   ├── EvalResult.kt                 (was: evaluation/EvalResult.kt)
│   ├── ValueConverters.kt            (was: evaluation/ValueConverters.kt)
│   └── MemoryStore.kt                (was: store/MemoryStore.kt — port consumed by Evaluator)
│
├── app/                               ← NEW
│   ├── LifecycleController.kt        (was: LifecycleController.kt)
│   ├── FlagTrackerImpl.kt            (was: changetracker/FlagTrackerImpl.kt)
│   └── DefaultMemoryStore.kt         (was: store/DefaultMemoryStore.kt — adapter for the domain port)
│
├── data/
│   ├── sync/                          ← NEW
│   │   ├── DataSynchronizer.kt       (was: datasynchronizer/DataSynchronizer.kt)
│   │   ├── NullDataSynchronizer.kt   (was: datasynchronizer/NullDataSynchronizer.kt)
│   │   ├── PollingDataSynchronizer.kt(was: datasynchronizer/PollingDataSynchronizer.kt)
│   │   └── StreamingDataSynchronizer.kt (was: datasynchronizer/StreamingDataSynchronizer.kt)
│   │
│   ├── http/                          ← NEW
│   │   ├── FbApiClient.kt            (was: internal/FbApiClient.kt)
│   │   ├── FBEndpoints.kt            (was: internal/FBEndpoints.kt)
│   │   ├── HttpConstants.kt          (was: internal/HttpConstants.kt)
│   │   ├── GetUserFlags.kt           (was: internal/GetUserFlags.kt)
│   │   └── ConnectionToken.kt        (was: internal/ConnectionToken.kt)
│   │
│   └── insights/                      ← NEW
│       ├── TrackInsight.kt           (was: internal/TrackInsight.kt)
│       └── InsightDispatcher.kt      (was: internal/InsightDispatcher.kt)
│
└── wire/                              ← NEW
    ├── EndUser.kt                    (was: model/EndUser.kt)
    └── Insight.kt                    (was: model/Insight.kt — Insight + VariationInsight + VariationData)
```

**Important corrections vs. brainstorm:**
- `FeatureFlag.kt` STAYS at `co.featbit.client.model.FeatureFlag` (full public FQN preserved). It's used by `FBClient.allFlags(): Map<String, FeatureFlag>` and external SDK consumers.
- `EvalDetail.kt` STAYS at `co.featbit.client.evaluation.EvalDetail` for the same reason.
- `FBUser.kt` STAYS at `co.featbit.client.model.FBUser` for the same reason.
- `FlagTracker.kt` STAYS at `co.featbit.client.changetracker.FlagTracker` for the same reason.

Tests mirror source paths and move alongside their classes.

---

## Verification gates (run at end of every task)

After EACH commit:
1. `./gradlew :featbit-client:compileDebugKotlin` — green
2. `./gradlew :featbit-client:testDebugUnitTest` — 98 pass / 0 fail / 2 e2e-skipped
3. `./gradlew :featbit-client:assembleDebug :featbit-client-android:assembleDebug :example-app:assembleDebug` — green
4. Audit on diff (cavecrew-reviewer or superpowers:code-reviewer) — zero blocking findings

Re-run E2E with `FEATBIT_E2E=1` only at the END (Task 7) — saves ~5 min per task otherwise.

---

## Task 1: Move `evaluation/{Evaluator,EvalResult,ValueConverters}` → `domain/`

**Files:**
- Move: `featbit-client/src/main/kotlin/co/featbit/client/evaluation/Evaluator.kt` → `featbit-client/src/main/kotlin/co/featbit/client/domain/Evaluator.kt`
- Move: `featbit-client/src/main/kotlin/co/featbit/client/evaluation/EvalResult.kt` → `featbit-client/src/main/kotlin/co/featbit/client/domain/EvalResult.kt`
- Move: `featbit-client/src/main/kotlin/co/featbit/client/evaluation/ValueConverters.kt` → `featbit-client/src/main/kotlin/co/featbit/client/domain/ValueConverters.kt`
- Move: `featbit-client/src/test/kotlin/co/featbit/client/evaluation/EvalResultTest.kt` → `featbit-client/src/test/kotlin/co/featbit/client/domain/EvalResultTest.kt`
- Move: `featbit-client/src/test/kotlin/co/featbit/client/evaluation/ValueConvertersTest.kt` → `featbit-client/src/test/kotlin/co/featbit/client/domain/ValueConvertersTest.kt`
- Modify: `featbit-client/src/main/kotlin/co/featbit/client/FBClientImpl.kt` (update imports)

NOTE: `EvalDetail.kt` STAYS in `evaluation/` — it is public API.

- [ ] **Step 1: Move source files with `git mv`**

```bash
cd /Users/deep.shah_fluentinhe/Documents/code/featbit-android-sdk/featbit-android-sdk
mkdir -p featbit-client/src/main/kotlin/co/featbit/client/domain
mkdir -p featbit-client/src/test/kotlin/co/featbit/client/domain
git mv featbit-client/src/main/kotlin/co/featbit/client/evaluation/Evaluator.kt \
       featbit-client/src/main/kotlin/co/featbit/client/domain/Evaluator.kt
git mv featbit-client/src/main/kotlin/co/featbit/client/evaluation/EvalResult.kt \
       featbit-client/src/main/kotlin/co/featbit/client/domain/EvalResult.kt
git mv featbit-client/src/main/kotlin/co/featbit/client/evaluation/ValueConverters.kt \
       featbit-client/src/main/kotlin/co/featbit/client/domain/ValueConverters.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/evaluation/EvalResultTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/domain/EvalResultTest.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/evaluation/ValueConvertersTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/domain/ValueConvertersTest.kt
```

- [ ] **Step 2: Update package declarations in moved files**

In each of the 5 moved files, change the `package co.featbit.client.evaluation` line to `package co.featbit.client.domain`. Use Edit tool, NOT sed (preserves line endings).

- [ ] **Step 3: Update imports in FBClientImpl.kt**

In `featbit-client/src/main/kotlin/co/featbit/client/FBClientImpl.kt`, change:
```
import co.featbit.client.evaluation.EvalResult
import co.featbit.client.evaluation.Evaluator
import co.featbit.client.evaluation.ValueConverter
import co.featbit.client.evaluation.ValueConverters
```
to:
```
import co.featbit.client.domain.EvalResult
import co.featbit.client.domain.Evaluator
import co.featbit.client.domain.ValueConverter
import co.featbit.client.domain.ValueConverters
```

Verify: `grep "co.featbit.client.evaluation" featbit-client/src/main/kotlin/co/featbit/client/FBClientImpl.kt` should now only match `EvalDetail` (which stays in evaluation/).

- [ ] **Step 4: Verify no stale references remain**

```bash
grep -rn "co.featbit.client.evaluation.Evaluator\|co.featbit.client.evaluation.EvalResult\|co.featbit.client.evaluation.ValueConverter" featbit-client/src/ 2>/dev/null
```
Expected: zero output.

- [ ] **Step 5: Run gates**

```bash
./gradlew :featbit-client:compileDebugKotlin 2>&1 | tail -5
./gradlew :featbit-client:testDebugUnitTest 2>&1 | tail -3
./gradlew :featbit-client:assembleDebug :featbit-client-android:assembleDebug :example-app:assembleDebug 2>&1 | tail -3
```
Expected: BUILD SUCCESSFUL on all three. Test count: 98 pass / 0 fail / 2 e2e-skipped.

- [ ] **Step 6: Audit on diff via cavecrew-reviewer (compressed)**

Dispatch: `caveman:cavecrew-reviewer` on `git diff HEAD` for this branch. Expected: zero blocking findings (pure file moves + package decl updates + import updates).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move evaluator + value converters into domain package

Pure Kotlin types (no Android / OkHttp / kotlinx-serialization deps)
relocated into co.featbit.client.domain.* to make the dependency rule
explicit. Public EvalDetail stays in evaluation/ (consumer-visible FQN).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Split store, move LifecycleController + FlagTrackerImpl into `app/`, MemoryStore port into `domain/`

**Revised per Task 1 audit** (ports/adapters pattern):
- `MemoryStore.kt` (interface) → `domain/` ← port; `Evaluator` already imports it
- `DefaultMemoryStore.kt` (impl) → `app/` ← adapter
- `FlagValueChangedEvent.kt` (contains `FlagValueChangedEvent` + `FlagChangeListener`) → **STAYS** in `store/` (public API via `FlagTracker.subscribe(FlagChangeListener)` — moving breaks consumers)
- `LifecycleController.kt` → `app/`
- `changetracker/FlagTrackerImpl.kt` → `app/`

**Files:**
- Move: `featbit-client/src/main/kotlin/co/featbit/client/LifecycleController.kt` → `featbit-client/src/main/kotlin/co/featbit/client/app/LifecycleController.kt`
- Move: `featbit-client/src/main/kotlin/co/featbit/client/changetracker/FlagTrackerImpl.kt` → `featbit-client/src/main/kotlin/co/featbit/client/app/FlagTrackerImpl.kt`
- Move: `featbit-client/src/main/kotlin/co/featbit/client/store/MemoryStore.kt` → `featbit-client/src/main/kotlin/co/featbit/client/domain/MemoryStore.kt` *(port)*
- Move: `featbit-client/src/main/kotlin/co/featbit/client/store/DefaultMemoryStore.kt` → `featbit-client/src/main/kotlin/co/featbit/client/app/DefaultMemoryStore.kt` *(adapter)*
- STAYS: `featbit-client/src/main/kotlin/co/featbit/client/store/FlagValueChangedEvent.kt` (no move — public API)
- Move tests: `LifecycleControllerTest.kt`, `FlagTrackerImplTest.kt`, `DefaultMemoryStoreTest.kt` into `app/`

- [ ] **Step 1: Verify `FlagValueChangedEvent + FlagChangeListener` are public API**

```bash
grep -n "FlagValueChangedEvent\|FlagChangeListener" featbit-client/src/main/kotlin/co/featbit/client/changetracker/FlagTracker.kt
```
Expected: hits referencing both as parameter / return types of public `FlagTracker.*`. Confirms `co.featbit.client.store.FlagValueChangedEvent` + `co.featbit.client.store.FlagChangeListener` FQNs must NOT move.

- [ ] **Step 2: Move source + test files**

```bash
cd /Users/deep.shah_fluentinhe/Documents/code/featbit-android-sdk/featbit-android-sdk
mkdir -p featbit-client/src/main/kotlin/co/featbit/client/app
mkdir -p featbit-client/src/test/kotlin/co/featbit/client/app

# MemoryStore interface → domain (port)
git mv featbit-client/src/main/kotlin/co/featbit/client/store/MemoryStore.kt \
       featbit-client/src/main/kotlin/co/featbit/client/domain/MemoryStore.kt

# DefaultMemoryStore impl → app (adapter)
git mv featbit-client/src/main/kotlin/co/featbit/client/store/DefaultMemoryStore.kt \
       featbit-client/src/main/kotlin/co/featbit/client/app/DefaultMemoryStore.kt

# FlagValueChangedEvent STAYS — do not move

# Application services → app
git mv featbit-client/src/main/kotlin/co/featbit/client/LifecycleController.kt \
       featbit-client/src/main/kotlin/co/featbit/client/app/LifecycleController.kt
git mv featbit-client/src/main/kotlin/co/featbit/client/changetracker/FlagTrackerImpl.kt \
       featbit-client/src/main/kotlin/co/featbit/client/app/FlagTrackerImpl.kt

# Tests
git mv featbit-client/src/test/kotlin/co/featbit/client/LifecycleControllerTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/app/LifecycleControllerTest.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/changetracker/FlagTrackerImplTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/app/FlagTrackerImplTest.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/store/DefaultMemoryStoreTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/app/DefaultMemoryStoreTest.kt
```

- [ ] **Step 3: Update package declarations in moved files**

Use Edit tool on each:
- `domain/MemoryStore.kt`: `package co.featbit.client.store` → `package co.featbit.client.domain`
- `app/DefaultMemoryStore.kt`: `package co.featbit.client.store` → `package co.featbit.client.app`
- `app/LifecycleController.kt`: `package co.featbit.client` → `package co.featbit.client.app`
- `app/FlagTrackerImpl.kt`: `package co.featbit.client.changetracker` → `package co.featbit.client.app`
- `app/DefaultMemoryStoreTest.kt`: `package co.featbit.client.store` → `package co.featbit.client.app`
- `app/LifecycleControllerTest.kt`: `package co.featbit.client` → `package co.featbit.client.app`
- `app/FlagTrackerImplTest.kt`: `package co.featbit.client.changetracker` → `package co.featbit.client.app`

`FlagValueChangedEvent.kt` (still in `store/`) unchanged.

- [ ] **Step 4: Update imports inside moved files**

These need their imports rewritten because their dependencies are now in different packages:

**`domain/MemoryStore.kt`** — was `package store`. References `FeatureFlag` (was implicit same-package or imported?). Check + add `import co.featbit.client.model.FeatureFlag` if missing.

**`app/DefaultMemoryStore.kt`** — was `package store`. Implements `MemoryStore` (now in `domain/`) + references `FlagChangeListener` + `FlagValueChangedEvent` (still in `store/`). Add:
```
import co.featbit.client.domain.MemoryStore
import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.FlagValueChangedEvent
```

**`app/FlagTrackerImpl.kt`** — was `package changetracker`. References `MemoryStore` (now `domain/`), `FlagChangeListener` + `FlagValueChangedEvent` (still `store/`). Update imports:
```
import co.featbit.client.domain.MemoryStore
import co.featbit.client.store.FlagChangeListener
import co.featbit.client.store.FlagValueChangedEvent
```

**`app/LifecycleController.kt`** — was `package co.featbit.client`. Imports `DataSynchronizer` from `co.featbit.client.datasynchronizer` (unchanged in Task 2). No store/changetracker imports to update.

**`app/DefaultMemoryStoreTest.kt`** — was `package store`. Test file. Update imports for `MemoryStore` (now domain) + `FlagChangeListener` / `FlagValueChangedEvent` (still store).

**`app/FlagTrackerImplTest.kt`** — was `package changetracker`. Update similarly.

**`app/LifecycleControllerTest.kt`** — was `package co.featbit.client`. References `DataSynchronizer` only — no changes.

- [ ] **Step 5: Update imports in EXTERNAL callers**

```bash
grep -rn "import co.featbit.client.store\.MemoryStore\|import co.featbit.client.LifecycleController$\|import co.featbit.client.changetracker.FlagTrackerImpl" featbit-client/src/ 2>/dev/null
```

For each match:
- `co.featbit.client.store.MemoryStore` → `co.featbit.client.domain.MemoryStore`
- `co.featbit.client.LifecycleController` → `co.featbit.client.app.LifecycleController`
- `co.featbit.client.changetracker.FlagTrackerImpl` → `co.featbit.client.app.FlagTrackerImpl`
- `co.featbit.client.store.DefaultMemoryStore` → `co.featbit.client.app.DefaultMemoryStore` (if any)

Known callers (verified upfront):
- `FBClientImpl.kt` — imports `LifecycleController`, `FlagTrackerImpl`, `MemoryStore`, `DefaultMemoryStore`.
- `domain/Evaluator.kt` — imports `co.featbit.client.store.MemoryStore` (from Task 1). Update to `co.featbit.client.domain.MemoryStore`.
- `data.sync/PollingDataSynchronizer.kt` / `StreamingDataSynchronizer.kt` (still in `datasynchronizer/` at this point, see file path) — import `co.featbit.client.store.MemoryStore`. Update to `domain.MemoryStore`.

Note: `FlagValueChangedEvent` + `FlagChangeListener` imports STAY at `co.featbit.client.store.*` — don't rewrite those.

- [ ] **Step 6: Verify no stale references**

```bash
grep -rn "co.featbit.client.store\.MemoryStore\|co.featbit.client.store\.DefaultMemoryStore" featbit-client/src/ 2>/dev/null
grep -rn "^import co.featbit.client.LifecycleController$\|co.featbit.client.changetracker.FlagTrackerImpl" featbit-client/src/ 2>/dev/null
```
Expected: zero output for both greps.

```bash
grep -rn "co.featbit.client.store.FlagValueChangedEvent\|co.featbit.client.store.FlagChangeListener" featbit-client/src/ 2>/dev/null
```
Expected: SOME output — these stay in `store/` (public API). Confirms public API FQN preserved.

- [ ] **Step 7: Run gates**

```bash
./gradlew :featbit-client:compileDebugKotlin
./gradlew :featbit-client:testDebugUnitTest
./gradlew :featbit-client:assembleDebug :featbit-client-android:assembleDebug :example-app:assembleDebug
```
Expected: all green, 98 pass / 0 fail / 2 e2e-skipped.

- [ ] **Step 8: Audit + commit**

Dispatch a code-reviewer agent with stance: "ports/adapters integrity" or "lifecycle of MemoryStore now that interface + impl live in different packages". Avoid same audit prompt as Task 1.

```bash
git add -A
git commit -m "refactor: split MemoryStore into domain port + app adapter

MemoryStore (interface) → domain/ as the port consumed by Evaluator.
DefaultMemoryStore (impl) → app/ as the adapter. FlagValueChangedEvent
+ FlagChangeListener stay in store/ — they are public API via
FlagTracker.subscribe(FlagChangeListener).

LifecycleController + FlagTrackerImpl move into app/ as application
services.

Closes the domain→app reverse arrow surfaced by Task 1 audit
(domain.Evaluator imported store.MemoryStore; would have become
domain→app once store/ moved into app/).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Move `datasynchronizer/*` → `data/sync/`

**Files:**
- Move: `featbit-client/src/main/kotlin/co/featbit/client/datasynchronizer/DataSynchronizer.kt` → `featbit-client/src/main/kotlin/co/featbit/client/data/sync/DataSynchronizer.kt`
- Move: `featbit-client/src/main/kotlin/co/featbit/client/datasynchronizer/NullDataSynchronizer.kt` → `featbit-client/src/main/kotlin/co/featbit/client/data/sync/NullDataSynchronizer.kt`
- Move: `featbit-client/src/main/kotlin/co/featbit/client/datasynchronizer/PollingDataSynchronizer.kt` → `featbit-client/src/main/kotlin/co/featbit/client/data/sync/PollingDataSynchronizer.kt`
- Move: `featbit-client/src/main/kotlin/co/featbit/client/datasynchronizer/StreamingDataSynchronizer.kt` → `featbit-client/src/main/kotlin/co/featbit/client/data/sync/StreamingDataSynchronizer.kt`
- Move tests to mirror.

- [ ] **Step 1: Move files**

```bash
mkdir -p featbit-client/src/main/kotlin/co/featbit/client/data/sync
mkdir -p featbit-client/src/test/kotlin/co/featbit/client/data/sync
for f in DataSynchronizer NullDataSynchronizer PollingDataSynchronizer StreamingDataSynchronizer; do
  git mv featbit-client/src/main/kotlin/co/featbit/client/datasynchronizer/${f}.kt \
         featbit-client/src/main/kotlin/co/featbit/client/data/sync/${f}.kt
done
git mv featbit-client/src/test/kotlin/co/featbit/client/datasynchronizer/PollingDataSynchronizerTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/data/sync/PollingDataSynchronizerTest.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/datasynchronizer/StreamingDataSynchronizerTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/data/sync/StreamingDataSynchronizerTest.kt
```

- [ ] **Step 2: Update package declarations**

In each of the 6 moved files (4 prod + 2 test):
- `package co.featbit.client.datasynchronizer` → `package co.featbit.client.data.sync`

- [ ] **Step 3: Update imports in callers**

```bash
grep -rn "co.featbit.client.datasynchronizer\." featbit-client/src/ 2>/dev/null
```

For each match, change `co.featbit.client.datasynchronizer.<X>` → `co.featbit.client.data.sync.<X>`.

Expected callers:
- `FBClientImpl.kt` (imports `DataSynchronizer`, `NullDataSynchronizer`, `PollingDataSynchronizer`, `StreamingDataSynchronizer`).
- `LifecycleControllerTest.kt` (now in `app/`) — imports `DataSynchronizer`.
- The moved sync files themselves — internal package imports become same-package.

- [ ] **Step 4: Verify**

```bash
grep -rn "co.featbit.client.datasynchronizer" featbit-client/src/ 2>/dev/null
```
Expected: zero output.

- [ ] **Step 5: Run gates** (compileDebugKotlin + testDebugUnitTest + assembleDebug — same expected outputs).

- [ ] **Step 6: Audit + commit**

```bash
git add -A
git commit -m "refactor: move synchronizers into data/sync/

Synchronizer adapters are concrete data-layer impls. Move from the
junk-drawer-adjacent datasynchronizer/ into data/sync/.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Move HTTP plumbing `internal/{FbApiClient,FBEndpoints,HttpConstants,GetUserFlags,ConnectionToken}` → `data/http/`

**Files:**
- Move 5 source + 3 test files (ConnectionTokenTest, FBEndpointsTest, GetUserFlagsTest).

- [ ] **Step 1: Move files**

```bash
mkdir -p featbit-client/src/main/kotlin/co/featbit/client/data/http
mkdir -p featbit-client/src/test/kotlin/co/featbit/client/data/http
for f in FbApiClient FBEndpoints HttpConstants GetUserFlags ConnectionToken; do
  git mv featbit-client/src/main/kotlin/co/featbit/client/internal/${f}.kt \
         featbit-client/src/main/kotlin/co/featbit/client/data/http/${f}.kt
done
git mv featbit-client/src/test/kotlin/co/featbit/client/internal/ConnectionTokenTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/data/http/ConnectionTokenTest.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/internal/FBEndpointsTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/data/http/FBEndpointsTest.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/internal/GetUserFlagsTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/data/http/GetUserFlagsTest.kt
```

- [ ] **Step 2: Update package declarations** (5 prod + 3 test files, `co.featbit.client.internal` → `co.featbit.client.data.http`).

- [ ] **Step 3: Update callers**

```bash
grep -rn "co.featbit.client.internal\.\(FbApiClient\|FBEndpoints\|HttpConstants\|GetUserFlags\|ConnectionToken\)" featbit-client/src/ 2>/dev/null
```

Replace `co.featbit.client.internal.<X>` with `co.featbit.client.data.http.<X>` for those 5 names.

Known callers:
- `FBClientImpl.kt` — `FBEndpoints`, `HttpTrackInsight` (latter still in internal/ until Task 5).
- `PollingDataSynchronizer.kt` (now in `data/sync/`) — imports `FBEndpoints`, `GetUserFlags`.
- `StreamingDataSynchronizer.kt` (now in `data/sync/`) — imports `ConnectionToken`, `FBEndpoints`.
- `TrackInsight.kt` (still in `internal/` until Task 5) — imports `FbApiClient`, `FBEndpoints`.

- [ ] **Step 4: Verify** (no stale internal.{Fb…|FB…|Http…|Get…|Con…} references).

- [ ] **Step 5: Run gates**.

- [ ] **Step 6: Audit + commit**

```bash
git add -A
git commit -m "refactor: move HTTP plumbing into data/http/

FbApiClient, FBEndpoints, HttpConstants, GetUserFlags, ConnectionToken —
all OkHttp-bound HTTP adapter code. Move from junk-drawer internal/ into
data/http/.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Move `internal/{TrackInsight,InsightDispatcher}` → `data/insights/`

**Files:**
- Move 2 source + 2 test files (InsightDispatcherTest, TrackInsightTest).

- [ ] **Step 1: Move files**

```bash
mkdir -p featbit-client/src/main/kotlin/co/featbit/client/data/insights
mkdir -p featbit-client/src/test/kotlin/co/featbit/client/data/insights
git mv featbit-client/src/main/kotlin/co/featbit/client/internal/TrackInsight.kt \
       featbit-client/src/main/kotlin/co/featbit/client/data/insights/TrackInsight.kt
git mv featbit-client/src/main/kotlin/co/featbit/client/internal/InsightDispatcher.kt \
       featbit-client/src/main/kotlin/co/featbit/client/data/insights/InsightDispatcher.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/internal/InsightDispatcherTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/data/insights/InsightDispatcherTest.kt
git mv featbit-client/src/test/kotlin/co/featbit/client/internal/TrackInsightTest.kt \
       featbit-client/src/test/kotlin/co/featbit/client/data/insights/TrackInsightTest.kt
```

- [ ] **Step 2: Update package declarations** (2 prod + 2 test, `co.featbit.client.internal` → `co.featbit.client.data.insights`).

- [ ] **Step 3: Update callers**

```bash
grep -rn "co.featbit.client.internal\.\(TrackInsight\|InsightDispatcher\|NoopTrackInsight\|HttpTrackInsight\)" featbit-client/src/ 2>/dev/null
```

Known caller:
- `FBClientImpl.kt` — imports `HttpTrackInsight`, `InsightDispatcher`, `NoopTrackInsight`, `TrackInsight`.

Replace each with `co.featbit.client.data.insights.<X>`.

- [ ] **Step 4: Verify `internal/` directory is now empty**

```bash
ls featbit-client/src/main/kotlin/co/featbit/client/internal/ 2>/dev/null
ls featbit-client/src/test/kotlin/co/featbit/client/internal/ 2>/dev/null
```
Expected: empty (no files). If empty, remove the directory:
```bash
rmdir featbit-client/src/main/kotlin/co/featbit/client/internal
rmdir featbit-client/src/test/kotlin/co/featbit/client/internal
```

- [ ] **Step 5: Run gates** + audit + commit.

```bash
git add -A
git commit -m "refactor: move insight pipeline into data/insights/

TrackInsight + InsightDispatcher are the analytics surface. With this
move, the internal/ junk drawer is dissolved.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Move wire DTOs `model/{EndUser,Insight}` → `wire/`

**Files:**
- Move 2 source files. There is no test for `EndUser` / `Insight` directly; `BuildersTest` exists in `model/` and tests `FBUser` (which STAYS in `model/`).

NOTE: `FBUser.kt` STAYS in `model/`. `FeatureFlag.kt` STAYS in `model/`. ONLY `EndUser.kt` and `Insight.kt` move.

- [ ] **Step 1: Verify `EndUser` and `Insight` are `internal`**

```bash
head -20 featbit-client/src/main/kotlin/co/featbit/client/model/EndUser.kt | grep -E "^internal|^public"
head -20 featbit-client/src/main/kotlin/co/featbit/client/model/Insight.kt | grep -E "^internal|^public"
```
Expected: both show `internal data class`. (If either is `public`, this move breaks consumers — abort + downgrade visibility plan separately.)

- [ ] **Step 2: Move files**

```bash
mkdir -p featbit-client/src/main/kotlin/co/featbit/client/wire
git mv featbit-client/src/main/kotlin/co/featbit/client/model/EndUser.kt \
       featbit-client/src/main/kotlin/co/featbit/client/wire/EndUser.kt
git mv featbit-client/src/main/kotlin/co/featbit/client/model/Insight.kt \
       featbit-client/src/main/kotlin/co/featbit/client/wire/Insight.kt
```

- [ ] **Step 3: Update package declarations** (2 files, `co.featbit.client.model` → `co.featbit.client.wire`).

- [ ] **Step 4: Update callers**

```bash
grep -rn "co.featbit.client.model.EndUser\|co.featbit.client.model.Insight\|co.featbit.client.model.VariationInsight\|co.featbit.client.model.VariationData" featbit-client/src/ 2>/dev/null
```

Replace each occurrence's `model` segment with `wire`.

Known callers (verified upfront):
- `FBClientImpl.kt` — imports `Insight`.
- `GetUserFlags.kt` (now in `data/http/`) — imports `EndUser`.
- `StreamingDataSynchronizer.kt` (now in `data/sync/`) — imports `EndUser`.
- `TrackInsight.kt` (now in `data/insights/`) — imports `Insight`.
- `InsightDispatcherTest.kt` (now in `data/insights/`) — imports `Insight`.
- `BuildersTest.kt` (stays in `model/`) — imports `EndUser` and uses `CustomizedProperty` (which lives inside `EndUser.kt`).

- [ ] **Step 5: Verify**

```bash
grep -rn "co.featbit.client.model.EndUser\|co.featbit.client.model.Insight" featbit-client/src/ 2>/dev/null
```
Expected: zero output. (`model.FBUser` and `model.FeatureFlag` references must remain — they're public.)

- [ ] **Step 6: Run gates** + audit + commit.

```bash
git add -A
git commit -m "refactor: move wire DTOs into wire/

EndUser + Insight (+ VariationInsight + VariationData) are internal
kotlinx-serialization DTOs — separate them from public domain types
(FBUser, FeatureFlag) so future @Serializable annotations never
accidentally creep onto domain models.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Final E2E + close-out

**Files:**
- No code changes; final verification + cleanup of leftover directories.

- [ ] **Step 1: Verify all old directories are empty + remove them**

```bash
for d in featbit-client/src/main/kotlin/co/featbit/client/changetracker \
         featbit-client/src/main/kotlin/co/featbit/client/datasynchronizer \
         featbit-client/src/main/kotlin/co/featbit/client/internal \
         featbit-client/src/main/kotlin/co/featbit/client/store \
         featbit-client/src/test/kotlin/co/featbit/client/changetracker \
         featbit-client/src/test/kotlin/co/featbit/client/datasynchronizer \
         featbit-client/src/test/kotlin/co/featbit/client/internal \
         featbit-client/src/test/kotlin/co/featbit/client/store; do
  if [ -d "$d" ] && [ -z "$(ls -A "$d")" ]; then rmdir "$d"; fi
done
```

NOTE: Directories that survive (do NOT remove):
- `changetracker/` keeps `FlagTracker.kt` (public interface)
- `model/` keeps `FBUser.kt` + `FeatureFlag.kt`
- `evaluation/` keeps `EvalDetail.kt`
- `store/` keeps `FlagValueChangedEvent.kt` (which contains `FlagValueChangedEvent` + `FlagChangeListener`, both public via `FlagTracker`)
- `options/` keeps `FBOptions.kt` + `DataSyncMode.kt`

The `rmdir` loop above is safe — it skips non-empty dirs.

- [ ] **Step 2: Verify the final target layout**

```bash
find featbit-client/src/main/kotlin/co/featbit/client -type d | sort
```
Expected (subset):
```
.../co/featbit/client
.../co/featbit/client/app
.../co/featbit/client/changetracker
.../co/featbit/client/data
.../co/featbit/client/data/http
.../co/featbit/client/data/insights
.../co/featbit/client/data/sync
.../co/featbit/client/domain
.../co/featbit/client/evaluation
.../co/featbit/client/model
.../co/featbit/client/options
.../co/featbit/client/wire
```

- [ ] **Step 3: Dependency-rule sanity check**

Domain must not import from data, app, wire, or any framework:
```bash
grep -rn "^import" featbit-client/src/main/kotlin/co/featbit/client/domain/ 2>/dev/null \
  | grep -v "^[^:]*:import kotlin\|^[^:]*:import co.featbit.client.model.\(FBUser\|FeatureFlag\)$"
```
Expected: zero output (other than `kotlin.*` stdlib imports + public `model.FBUser` / `model.FeatureFlag` references if any).

If `domain/` has any `co.featbit.client.app.`, `co.featbit.client.data.`, `co.featbit.client.wire.`, `okhttp3.`, `kotlinx.serialization.`, or `android.` imports — that's a dependency-rule violation; investigate.

- [ ] **Step 4: Run full gate + E2E against real FeatBit stack**

```bash
./gradlew :featbit-client:assembleDebug :featbit-client-android:assembleDebug :example-app:assembleDebug
./gradlew :featbit-client:testDebugUnitTest
DOCKER_HOST="unix:///Users/deep.shah_fluentinhe/.colima/default/docker.sock" \
  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock" \
  FEATBIT_E2E=1 ./gradlew :featbit-client:testDebugUnitTest --tests "co.featbit.client.e2e.*"
```
Expected:
- assembleDebug: green for all 3 modules.
- Unit tests: 98 pass / 0 fail / 2 e2e-skipped (e2e skipped without env var).
- With FEATBIT_E2E=1: both `FeatBitE2ETest` + `FeatBitStreamingE2ETest` pass against Colima FeatBit.

- [ ] **Step 5: Update `docs/superpowers/architecture.md`**

Open `docs/superpowers/architecture.md`. Update the "Module / package layout" section's tree to match the new layout (the one produced by Step 2). Same content elsewhere — just the directory diagram changes.

- [ ] **Step 6: Final audit on whole-branch diff vs main**

Dispatch `superpowers:code-reviewer` (wider scope) on the diff `git diff main...refactor/clean-architecture -- 'featbit-client/**'`. Expected: zero blocking findings. Tighten any audit-flagged items inline if NIT.

- [ ] **Step 7: Commit closeout**

```bash
git add -A
git commit -m "refactor: prune empty package directories + update architecture doc

Wraps up the clean-architecture pass. Final layout: domain/, app/,
data/{sync,http,insights}/, wire/ — plus the unchanged public-API
packages (root, options/, model/, evaluation/, changetracker/).

E2E verified against real FeatBit stack (Colima).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Self-review (writing-plans skill checklist, run inline)

- **Spec coverage:** Every move-table row in the spec maps to a Task 1-6 step. Final layout (Task 7 Step 2) matches the spec's target layout block.
- **Placeholder scan:** No TBD / TODO. Every step has either bash commands or explicit Edit-tool instructions.
- **Type consistency:** Package FQNs used consistently — `co.featbit.client.domain`, `co.featbit.client.app`, `co.featbit.client.data.sync`, `co.featbit.client.data.http`, `co.featbit.client.data.insights`, `co.featbit.client.wire`. Public types and their original FQNs explicitly preserved in each task's NOTE block.
- **Verification gates uniform:** every code-touching task has compile + test + assemble + audit + commit steps.

Issue caught + fixed inline: Task 6 needs to confirm `EndUser` and `Insight` are `internal` BEFORE moving — added Step 1 to verify.
