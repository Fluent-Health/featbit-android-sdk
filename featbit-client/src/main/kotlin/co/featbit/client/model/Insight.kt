package co.featbit.client.model

import kotlinx.serialization.Serializable

/**
 * An analytics event reported to the FeatBit insight endpoint.
 *
 * Two flavours exist, mirroring the .NET SDK:
 *  - a flag-evaluation insight carrying a single [VariationInsight];
 *  - a user-identify insight carrying an empty [variations] list.
 */
@Serializable
internal data class Insight(
    val user: EndUser,
    val variations: List<VariationInsight>,
) {
    companion object {
        fun forEvaluation(user: FBUser, flag: FeatureFlag, timestamp: Long): Insight =
            Insight(user.toEndUser(), listOf(VariationInsight.of(flag, timestamp)))

        fun forIdentify(user: FBUser): Insight =
            Insight(user.toEndUser(), emptyList())
    }
}

@Serializable
internal data class VariationInsight(
    val featureFlagKey: String,
    val variation: VariationData,
    val sendToExperiment: Boolean,
    val timestamp: Long,
) {
    companion object {
        fun of(flag: FeatureFlag, timestamp: Long): VariationInsight = VariationInsight(
            featureFlagKey = flag.id,
            variation = VariationData(flag.variationId, flag.variation),
            sendToExperiment = flag.sendToExperiment,
            timestamp = timestamp,
        )
    }
}

@Serializable
internal data class VariationData(
    val id: String,
    val value: String,
)
