package co.featbit.client.model

/**
 * Defines the attributes of a user for whom feature flags are evaluated.
 *
 * An [FBUser] has two built-in attributes — [key] and [name] — and any number of custom
 * string attributes. The only mandatory attribute is [key], which must uniquely identify
 * each user. Both built-in and custom attributes can be referenced in targeting rules and
 * are included in analytics data.
 *
 * Instances are created with the fluent [Builder]:
 * ```
 * val user = FBUser.builder("a-unique-key-of-bob")
 *     .name("bob")
 *     .custom("country", "FR")
 *     .build()
 * ```
 */
public class FBUser internal constructor(
    public val key: String,
    public val name: String,
    public val custom: Map<String, String>,
) {
    internal fun toEndUser(): EndUser = EndUser(
        keyId = key,
        name = name,
        customizedProperties = custom.map { (k, v) -> CustomizedProperty(k, v) },
    )

    /** Fluent builder for [FBUser]. */
    public class Builder(private val key: String) {
        private var name: String = ""
        private val custom: MutableMap<String, String> = LinkedHashMap()

        /** Sets the full name for the user. Blank values are ignored. */
        public fun name(name: String): Builder = apply {
            if (name.isNotBlank()) this.name = name
        }

        /**
         * Adds a custom attribute with a string value.
         *
         * @throws IllegalArgumentException if [key] is blank.
         */
        public fun custom(key: String, value: String): Builder = apply {
            require(key.isNotBlank()) { "key cannot be null or empty" }
            custom[key] = value
        }

        /** Builds an immutable [FBUser] from the configured properties. */
        public fun build(): FBUser = FBUser(key, name, custom.toMap())
    }

    public companion object {
        /**
         * Creates a [Builder] for constructing an [FBUser].
         *
         * @param key a string that uniquely identifies the user.
         */
        public fun builder(key: String): Builder = Builder(key)
    }
}
