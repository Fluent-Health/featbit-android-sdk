package co.featbit.client.model

import kotlinx.serialization.Serializable

/**
 * Wire representation of an [FBUser] as expected by the FeatBit evaluation and insight
 * endpoints. Mirrors the shape produced by `FbUser.AsEndUser()` in the .NET SDK.
 */
@Serializable
internal data class EndUser(
    val keyId: String,
    val name: String,
    val customizedProperties: List<CustomizedProperty>,
)

@Serializable
internal data class CustomizedProperty(
    val name: String,
    val value: String,
)
