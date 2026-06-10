package co.featbit.client

import co.featbit.client.changetracker.FlagTracker
import co.featbit.client.changetracker.FlagTrackerImpl
import co.featbit.client.datasynchronizer.DataSynchronizer
import co.featbit.client.datasynchronizer.NullDataSynchronizer
import co.featbit.client.datasynchronizer.PollingDataSynchronizer
import co.featbit.client.datasynchronizer.StreamingDataSynchronizer
import co.featbit.client.evaluation.EvalDetail
import co.featbit.client.evaluation.Evaluator
import co.featbit.client.evaluation.ValueConverter
import co.featbit.client.evaluation.ValueConverters
import co.featbit.client.internal.HttpTrackInsight
import co.featbit.client.internal.NoopTrackInsight
import co.featbit.client.internal.TrackInsight
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import co.featbit.client.model.Insight
import co.featbit.client.options.DataSyncMode
import co.featbit.client.options.FBOptions
import co.featbit.client.store.DefaultMemoryStore
import co.featbit.client.store.MemoryStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Default [FBClient] implementation. Wires together the store, evaluator, flag tracker,
 * insight tracker, and data synchronizer, mirroring the .NET `FbClient`.
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
    private val trackInsight: TrackInsight =
        if (options.offline) NoopTrackInsight else HttpTrackInsight(options)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            logger.error("Unhandled error in FBClient background task.", t)
        },
    )

    @Volatile
    private var user: FBUser = initialUser

    @Volatile
    private var dataSynchronizer: DataSynchronizer = newDataSynchronizer(initialUser)

    private val lifecycle =
        LifecycleController(scope, options.backgroundGracePeriod.inWholeMilliseconds) { dataSynchronizer }

    override val initialized: Boolean get() = dataSynchronizer.initialized

    override val flagTracker: FlagTracker get() = flagTrackerImpl

    private fun newDataSynchronizer(forUser: FBUser): DataSynchronizer = when {
        options.offline -> NullDataSynchronizer()
        options.dataSyncMode == DataSyncMode.Streaming ->
            StreamingDataSynchronizer(options, forUser, store)
        options.dataSyncMode == DataSyncMode.Polling ->
            PollingDataSynchronizer(options, forUser, store)
        else -> NullDataSynchronizer()
    }

    override suspend fun start(timeout: Duration): Boolean {
        logger.info("Waiting up to $timeout for FBClient to start...")
        val success = withTimeoutOrNull(timeout) { dataSynchronizer.start() } ?: false
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

    override suspend fun identify(user: FBUser, timeout: Duration): Boolean {
        this.user = user

        // Dispose the current synchronizer and start a fresh one for the new user.
        dataSynchronizer.close()
        dataSynchronizer = newDataSynchronizer(user)

        val success = withTimeoutOrNull(timeout) { dataSynchronizer.start() } ?: false

        // Fire-and-forget the user insight.
        scope.launch { trackInsight.run(Insight.forIdentify(user)) }

        return success
    }

    override fun boolVariation(key: String, default: Boolean): Boolean =
        evaluateCore(key, default, ValueConverters.bool).value

    override fun boolVariationDetail(key: String, default: Boolean): EvalDetail<Boolean> =
        evaluateCore(key, default, ValueConverters.bool)

    override fun intVariation(key: String, default: Int): Int =
        evaluateCore(key, default, ValueConverters.int).value

    override fun intVariationDetail(key: String, default: Int): EvalDetail<Int> =
        evaluateCore(key, default, ValueConverters.int)

    override fun floatVariation(key: String, default: Float): Float =
        evaluateCore(key, default, ValueConverters.float).value

    override fun floatVariationDetail(key: String, default: Float): EvalDetail<Float> =
        evaluateCore(key, default, ValueConverters.float)

    override fun doubleVariation(key: String, default: Double): Double =
        evaluateCore(key, default, ValueConverters.double).value

    override fun doubleVariationDetail(key: String, default: Double): EvalDetail<Double> =
        evaluateCore(key, default, ValueConverters.double)

    override fun stringVariation(key: String, default: String): String =
        evaluateCore(key, default, ValueConverters.string).value

    override fun stringVariationDetail(key: String, default: String): EvalDetail<String> =
        evaluateCore(key, default, ValueConverters.string)

    override fun allFlags(): Map<String, FeatureFlag> = store.getAll().associateBy { it.id }

    override fun setForeground(foreground: Boolean) = lifecycle.onForegroundChanged(foreground)

    override fun setNetworkAvailable(available: Boolean) = lifecycle.onNetworkChanged(available)

    private fun <T> evaluateCore(
        key: String,
        default: T,
        converter: ValueConverter<T>,
    ): EvalDetail<T> {
        // Client not ready and no bootstrap data — always return the default value.
        if (!initialized && options.bootstrap.isEmpty()) {
            return EvalDetail("client not ready", default)
        }

        val (evalResult, flag) = evaluator.evaluate(key)
        if (!evalResult.isValid || flag == null) {
            return EvalDetail(evalResult.reason, default)
        }

        // Fire-and-forget the evaluation insight.
        scope.launch { trackInsight.run(Insight.forEvaluation(user, flag, System.currentTimeMillis())) }

        val typed = converter(evalResult.value)
        return if (typed != null) {
            EvalDetail(evalResult.reason, typed)
        } else {
            EvalDetail("type mismatch", default)
        }
    }

    override fun close() {
        dataSynchronizer.close()
        flagTrackerImpl.close()
        trackInsight.close()
        scope.cancel()
    }
}
