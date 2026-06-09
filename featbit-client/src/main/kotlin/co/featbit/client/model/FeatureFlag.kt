package co.featbit.client.model

import kotlinx.serialization.Serializable

/**
 * Represents a feature flag and its current evaluation result for the current user.
 *
 * This is the data returned by the FeatBit evaluation server and stored in the SDK's
 * in-memory store. Field names map directly to the server's camelCase JSON payload.
 */
@Serializable
public data class FeatureFlag(
    /** The unique key of the feature flag. */
    val id: String = "",
    /** The string representation of the evaluated variation value. */
    val variation: String = "",
    /** The declared type of the variation (e.g. `boolean`, `string`, `number`, `json`). */
    val variationType: String = "",
    /** The id of the evaluated variation. */
    val variationId: String = "",
    /** Whether evaluations of this flag should be sent to experimentation. */
    val sendToExperiment: Boolean = false,
    /** A human-readable description of why this variation was returned. */
    val matchReason: String = "",
)
