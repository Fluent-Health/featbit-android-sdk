package co.featbit.client

import co.featbit.client.app.DefaultMemoryStore
import co.featbit.client.app.FlagTrackerImpl
import co.featbit.client.app.LifecycleController
import co.featbit.client.changetracker.FlagTracker
import co.featbit.client.data.sync.DataSynchronizer
import co.featbit.client.data.sync.NullDataSynchronizer
import co.featbit.client.data.sync.PollingDataSynchronizer
import co.featbit.client.data.sync.StreamingDataSynchronizer
import co.featbit.client.domain.EvalResult
import co.featbit.client.domain.Evaluator
import co.featbit.client.domain.MemoryStore
import co.featbit.client.domain.ValueConverter
import co.featbit.client.domain.ValueConverters
import co.featbit.client.evaluation.EvalDetail
import co.featbit.client.data.http.FBEndpoints
import co.featbit.client.data.insights.HttpTrackInsight
import co.featbit.client.data.insights.InsightDispatcher
import co.featbit.client.data.insights.NoopTrackInsight
import co.featbit.client.data.insights.TrackInsight
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.wire.Insight
import co.featbit.client.options.DataSyncMode
import co.featbit.client.options.FBOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Default [FBClient] implementation. Wires together the store, evaluator, flag tracker,
 * insight pipeline, and data synchronizer, mirroring the .NET `FbClient`.
 *
 * @param options the client configuration.
 * @param initialUser the initial evaluation user; change it later with [identify].
 */
public class FBClientImpl(
    private val options: FBOptions,
    initialUser: FBUser,
) : FBClient {

    private val logger = options.logger
    private val store: MemoryStore = DefaultMemoryStore(options.bootstrap)
    private val evaluator = Evaluator(store)
    private val flagTrackerImpl = FlagTrackerImpl(store)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            logger.error("Unhandled error in FBClient background task.", t)
        },
    )

    // Single, eagerly-parsed source of truth for the FeatBit URLs we talk to. Constructing it
    // once (rather than once per HTTP client) keeps "centralization" honest and surfaces any
    // malformed configuration at SDK init.
    private val endpoints: FBEndpoints? =
        if (options.offline) null else FBEndpoints.from(options)

    // Insight pipeline — bounded, batched, non-blocking on the evaluation hot path.
    private val tracker: TrackInsight =
        if (options.offline) NoopTrackInsight else HttpTrackInsight(options, endpoints = endpoints!!)
    private val insights: InsightDispatcher = InsightDispatcher(tracker, scope, logger)

    // When the tracker is the no-op (offline mode), every Insight we'd build is thrown away by
    // the consumer. Short-circuit at the *call site* so we don't allocate Insight +
    // VariationInsight + VariationData per evaluation just to discard them — that's ~3 garbage
    // objects per flag check the JVM never has to see in offline.
    private val insightsEnabled: Boolean = tracker !is NoopTrackInsight

    // user is read on every evaluation; AtomicReference gives lock-free reads + atomic swap on identify().
    private val userRef = AtomicReference(initialUser)

    /**
     * Active synchronizer holder. Replaced atomically on [identify]; the previous
     * synchronizer is closed under [identifyMutex] so its in-flight upserts cannot land
     * after the swap (those would otherwise contaminate the new user's flag store).
     */
    private val syncRef: AtomicReference<DataSynchronizer> =
        AtomicReference(newDataSynchronizer(initialUser))
    private val identifyMutex = Mutex()

    private val lifecycle =
        LifecycleController(scope, options.backgroundGracePeriod.inWholeMilliseconds) { syncRef.get() }

    override val initialized: Boolean get() = syncRef.get().initialized

    override val flagTracker: FlagTracker get() = flagTrackerImpl

    private fun newDataSynchronizer(forUser: FBUser): DataSynchronizer = when {
        options.offline -> NullDataSynchronizer()
        options.dataSyncMode == DataSyncMode.Streaming ->
            StreamingDataSynchronizer(options, forUser, store, endpoints = endpoints!!)
        options.dataSyncMode == DataSyncMode.Polling ->
            PollingDataSynchronizer(options, forUser, store, endpoints = endpoints!!)
        else -> NullDataSynchronizer()
    }

    override suspend fun start(timeout: Duration): Boolean {
        logger.info("Waiting up to $timeout for FBClient to start...")
        val success = withTimeoutOrNull(timeout) { syncRef.get().start() } ?: false
        if (success) {
            logger.info("FBClient successfully started.")
        } else {
            logger.error(
                "FBClient failed to start within $timeout. This usually indicates a connection " +
                    "issue with FeatBit or an invalid secret. Double-check your secret and URLs.",
            )
        }
        return success
    }

    override suspend fun identify(user: FBUser, timeout: Duration): Boolean = identifyMutex.withLock {
        // Tear down the old synchronizer *and await its in-flight upserts* before installing a
        // new one. `close()` alone only cancels the scope (non-suspending); a polling response
        // already mid-`store.upsert` would race past the swap. `closeAndJoin` waits for that
        // upsert to finish before returning, guaranteeing no stale data lands under the new user.
        syncRef.get().closeAndJoin()
        val fresh = newDataSynchronizer(user)
        syncRef.set(fresh)
        userRef.set(user)

        val success = withTimeoutOrNull(timeout) { fresh.start() } ?: false

        if (insightsEnabled) insights.offer(Insight.forIdentify(user))
        success
    }

    override fun boolVariation(key: String, default: Boolean): Boolean =
        evaluateValue(key, default, ValueConverters.bool)

    override fun boolVariationDetail(key: String, default: Boolean): EvalDetail<Boolean> =
        evaluateCore(key, default, ValueConverters.bool)

    override fun intVariation(key: String, default: Int): Int =
        evaluateValue(key, default, ValueConverters.int)

    override fun intVariationDetail(key: String, default: Int): EvalDetail<Int> =
        evaluateCore(key, default, ValueConverters.int)

    override fun floatVariation(key: String, default: Float): Float =
        evaluateValue(key, default, ValueConverters.float)

    override fun floatVariationDetail(key: String, default: Float): EvalDetail<Float> =
        evaluateCore(key, default, ValueConverters.float)

    override fun doubleVariation(key: String, default: Double): Double =
        evaluateValue(key, default, ValueConverters.double)

    override fun doubleVariationDetail(key: String, default: Double): EvalDetail<Double> =
        evaluateCore(key, default, ValueConverters.double)

    override fun stringVariation(key: String, default: String): String =
        evaluateValue(key, default, ValueConverters.string)

    override fun stringVariationDetail(key: String, default: String): EvalDetail<String> =
        evaluateCore(key, default, ValueConverters.string)

    override fun allFlags(): Map<String, FeatureFlag> {
        // store.getAll() already returns a fresh Collection snapshot; calling .associateBy
        // here would walk it a second time + allocate the intermediate List. Build the result
        // map directly from the snapshot — one allocation, one pass.
        val snapshot = store.getAll()
        val result = LinkedHashMap<String, FeatureFlag>(snapshot.size)
        for (flag in snapshot) result[flag.id] = flag
        return result
    }

    override fun setForeground(foreground: Boolean): Unit = lifecycle.onForegroundChanged(foreground)

    override fun setNetworkAvailable(available: Boolean): Unit = lifecycle.onNetworkChanged(available)

    private fun <T> evaluateCore(
        key: String,
        default: T,
        converter: ValueConverter<T>,
    ): EvalDetail<T> {
        // Client not ready and no bootstrap data — always return the default value.
        if (!initialized && options.bootstrap.isEmpty()) {
            return EvalDetail("client not ready", default)
        }

        return when (val result = evaluator.evaluate(key)) {
            is EvalResult.NotFound -> EvalDetail(result.reason, default)
            is EvalResult.Found -> {
                if (insightsEnabled) {
                    insights.offer(
                        Insight.forEvaluation(userRef.get(), result.flag, System.currentTimeMillis()),
                    )
                }
                val typed = converter(result.value)
                if (typed != null) EvalDetail(result.reason, typed)
                else EvalDetail("type mismatch", default)
            }
        }
    }

    // Fast-path for `*Variation()` getters that discard `.reason` immediately. Skips the
    // EvalDetail allocation that evaluateCore() would otherwise build and the caller would
    // throw away. The detail-returning getters keep going through evaluateCore so they can
    // surface the per-call reason string.
    private fun <T> evaluateValue(key: String, default: T, converter: ValueConverter<T>): T {
        if (!initialized && options.bootstrap.isEmpty()) return default

        return when (val result = evaluator.evaluate(key)) {
            is EvalResult.NotFound -> default
            is EvalResult.Found -> {
                if (insightsEnabled) {
                    insights.offer(
                        Insight.forEvaluation(userRef.get(), result.flag, System.currentTimeMillis()),
                    )
                }
                converter(result.value) ?: default
            }
        }
    }

    override fun close() {
        // FBClient.close() is the public Closeable contract (non-suspending). Bridge to the
        // two suspending teardowns with independent per-phase budgets so a slow sync teardown
        // can't starve the insight drain (and vice versa) — the latter must reach
        // `tracker.close()` to release the underlying OkHttp dispatcher even under contention.
        runBlocking {
            withTimeoutOrNull(SYNC_CLOSE_TIMEOUT_MS) { syncRef.get().closeAndJoin() }
            withTimeoutOrNull(INSIGHTS_CLOSE_TIMEOUT_MS) { insights.closeAndDrain() }
        }
        flagTrackerImpl.close()
        scope.cancel()
    }

    private companion object {
        // Per-phase budgets. Total worst-case: 4s — well under the 10s Android ANR threshold,
        // and each phase has its own deadline so neither can starve the other.
        const val SYNC_CLOSE_TIMEOUT_MS: Long = 2_000L
        const val INSIGHTS_CLOSE_TIMEOUT_MS: Long = 2_000L
    }
}
