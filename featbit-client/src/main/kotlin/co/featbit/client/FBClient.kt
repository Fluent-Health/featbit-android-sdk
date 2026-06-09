package co.featbit.client

import co.featbit.client.changetracker.FlagTracker
import co.featbit.client.evaluation.EvalDetail
import co.featbit.client.model.FBUser
import co.featbit.client.model.FeatureFlag
import java.io.Closeable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The client for accessing FeatBit feature flags in a single-user (client-side) context.
 *
 * Applications should create and hold a **single instance** for the lifetime of the app.
 * Initialization and user switching are `suspend` functions; flag evaluations are synchronous
 * reads from the in-memory store.
 */
public interface FBClient : Closeable {
    /** Whether the client has finished initializing and is ready to evaluate flags. */
    public val initialized: Boolean

    /** The tracker used to subscribe to feature flag changes. */
    public val flagTracker: FlagTracker

    /**
     * Starts the client and suspends until it is ready or [timeout] elapses.
     *
     * @return `true` if the client became ready within [timeout].
     */
    public suspend fun start(timeout: Duration = 3.seconds): Boolean

    /**
     * Switches the current evaluation [user], fetches that user's flags, and reports a user
     * insight to FeatBit. Suspends until the new user's data is ready or [timeout] elapses.
     *
     * @return `true` if the user was identified within [timeout].
     */
    public suspend fun identify(user: FBUser, timeout: Duration = 3.seconds): Boolean

    public fun boolVariation(key: String, default: Boolean = false): Boolean
    public fun boolVariationDetail(key: String, default: Boolean = false): EvalDetail<Boolean>

    public fun intVariation(key: String, default: Int = 0): Int
    public fun intVariationDetail(key: String, default: Int = 0): EvalDetail<Int>

    public fun floatVariation(key: String, default: Float = 0f): Float
    public fun floatVariationDetail(key: String, default: Float = 0f): EvalDetail<Float>

    public fun doubleVariation(key: String, default: Double = 0.0): Double
    public fun doubleVariationDetail(key: String, default: Double = 0.0): EvalDetail<Double>

    public fun stringVariation(key: String, default: String = ""): String
    public fun stringVariationDetail(key: String, default: String = ""): EvalDetail<String>

    /** Returns a snapshot map of all known feature flags keyed by their flag key. */
    public fun allFlags(): Map<String, FeatureFlag>
}
