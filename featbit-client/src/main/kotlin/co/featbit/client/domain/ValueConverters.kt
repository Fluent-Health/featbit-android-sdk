package co.featbit.client.domain

/**
 * Converts the string variation value stored for a flag into a typed value.
 *
 * Each converter returns `null` to signal a type mismatch (the SDK then falls back to the
 * caller's default value). Inputs are trimmed and booleans are parsed case-insensitively to
 * mirror the lenient parsing of the .NET SDK's `ValueConverters`.
 */
internal typealias ValueConverter<T> = (String) -> T?

internal object ValueConverters {
    val bool: ValueConverter<Boolean> = { value ->
        // `value.trim().lowercase()` allocated two strings per check — `lowercase()`
        // unconditionally allocates when there is any uppercase letter. `equals(_, ignoreCase=true)`
        // compares without allocating, and the prior `trim()` handled exterior whitespace
        // which we keep with a single trim call. For clean server data both branches are O(1)
        // string comparisons.
        val trimmed = value.trim()
        when {
            trimmed.equals("true", ignoreCase = true) -> true
            trimmed.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }

    val string: ValueConverter<String> = { value -> value }

    val int: ValueConverter<Int> = { value -> value.trim().toIntOrNull() }

    val float: ValueConverter<Float> = { value -> value.trim().toFloatOrNull() }

    val double: ValueConverter<Double> = { value -> value.trim().toDoubleOrNull() }
}
