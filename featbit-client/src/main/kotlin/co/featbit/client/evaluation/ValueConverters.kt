package co.featbit.client.evaluation

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
        when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    val string: ValueConverter<String> = { value -> value }

    val int: ValueConverter<Int> = { value -> value.trim().toIntOrNull() }

    val float: ValueConverter<Float> = { value -> value.trim().toFloatOrNull() }

    val double: ValueConverter<Double> = { value -> value.trim().toDoubleOrNull() }
}
